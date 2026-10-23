package org.sopt.solply_server.domain.place.cache;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원본을 읽어 스냅샷의 재료 한 벌을 만든다. <b>번호와 데이터가 같은 시점의 것</b>이라는 보장이
 * 이 클래스의 유일한 계약이고, 그 보장은 트랜잭션의 모양에서 나온다.
 *
 * <p><b>읽기 트랜잭션의 세 가지 설정에 각각 이유가 있다.</b>
 * <ul>
 *   <li>{@code REQUIRES_NEW} — 부르는 쪽(폴 스레드·기동 초기화·요청 재개)의 트랜잭션에 얹히지
 *       않는다. 얹히면 격리 수준도 read view도 남의 것이 된다.</li>
 *   <li>{@code REPEATABLE_READ} 명시 — MySQL의 기본값이기도 하지만 기본값에 기대지 않는다.
 *       이 트랜잭션이 여러 테이블을 훑는 동안 다른 커밋이 끼어들어 <b>장소는 새 값, 태그는 옛
 *       값</b>인 반쪽 스냅샷이 나오는 것을 막는 것이 전부다. READ COMMITTED였다면 문장마다 새
 *       시야를 받아 그 창이 매번 열린다.</li>
 *   <li>{@code readOnly} — 커넥션을 읽기 전용으로 잡고 Hibernate의 flush를 끈다. 이 경로에서
 *       쓰기가 나가는 일은 사고다.</li>
 * </ul>
 *
 * <p><b>번호와 데이터가 같은 시점인 근거는 read view 하나다.</b> InnoDB의 REPEATABLE READ는
 * 트랜잭션의 <b>첫 일반 SELECT</b>에서 read view를 만들고, 그 뒤의 모든 문장이 같은 시점을 본다.
 * 그래서 번호를 먼저 읽든 나중에 읽든 값은 같다 — <b>번호를 먼저 읽는 것은 순서를 눈에 보이게
 * 고정하는 구현 규칙</b>이지 정합성의 조건이 아니다. 규칙을 두는 이유는 read view가 열리는
 * 문장이 어디인지가 코드에서 드러나야, 나중에 이 트랜잭션 앞에 다른 읽기가 끼어드는 변경을
 * 알아볼 수 있기 때문이다.
 *
 * <p>정합성이 실제로 걸려 있는 곳은 쓰기 쪽이다. 목록을 바꾸는 트랜잭션이 <b>같은 트랜잭션에서
 * 번호를 올리므로</b>, 이 read view가 어떤 변경을 보았다면 그 변경의 번호도 함께 본다. 반쪽만
 * 실린 스냅샷이 나올 수 없는 이유가 그것이다.
 *
 * <p><b>정렬·객체 조립은 트랜잭션 밖이다.</b> 트랜잭션 안에서 하는 일은 행을 읽어 오는 것까지고,
 * {@link PlaceEntry}·{@link PlaceView}·태그 맵 구성은 트랜잭션이 닫힌 뒤에 한다. DB 커넥션을
 * 객체를 만드는 동안 붙잡고 있을 이유가 없다. 정렬은 그보다 더 뒤 {@link SnapshotInstaller}가 한다.
 */
@Slf4j
@Component
public class SnapshotLoader {

    private final EntityManager em;
    private final SnapshotMetadataRepository metadataRepository;
    private final TransactionTemplate readTransaction;

    public SnapshotLoader(EntityManager em, SnapshotMetadataRepository metadataRepository,
            PlatformTransactionManager transactionManager) {
        this.em = em;
        this.metadataRepository = metadataRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setReadOnly(true);
    }

    private static final String LIST_SOURCE_SQL = """
            SELECT ps.place_id, ps.town_id, ps.tag_bitmask,
                   ps.popular_score, ps.created_at,
                   ps.bookmark_count, ps.review_count, ps.avg_rating,
                   ps.latitude, ps.longitude,
                   ps.name,
                   ps.main_tag_id,
                   ps.thumbnail_file_key
            FROM place_stats ps
            """;

    private static final String SOME_VIEWS_SQL = LIST_SOURCE_SQL + """
            WHERE ps.place_id IN (:placeIds)
            """;

    private static final String TAG_SOURCE_SQL = """
            SELECT t.id, t.name, t.active
            FROM tags t
            """;

    /**
     * 스냅샷 한 벌의 재료 전부와 <b>그것이 어느 시점인지</b>.
     *
     * @param metadata 읽기 트랜잭션이 본 번호 둘. 설치가 이 값을 그대로 싣는다
     */
    public record SourceState(
            SnapshotMetadata metadata,
            List<PlaceEntry> entries,
            ConcurrentMap<Long, PlaceView> views,
            Map<Long, TagView> tagViews) {
    }

    /**
     * 표시값 한 벌과 <b>그것이 어느 시점인지</b>. 즉시 패치가 이 관측 번호를 그대로 달고 간다 —
     * 부르는 쪽이 자기 쓰기의 번호를 따로 붙이면 <b>번호는 자기 것, 데이터는 그 뒤의 것</b>인
     * 어긋난 쌍이 된다.
     */
    public record ViewState(SnapshotMetadata metadata, Map<Long, PlaceView> views) {
    }

    /** 태그 표시값 한 벌과 그 관측 시점. {@link ViewState}와 같은 이유로 쌍이다. */
    public record TagViewState(SnapshotMetadata metadata, Map<Long, TagView> tagViews) {
    }

    /**
     * 전량을 한 시점으로 읽는다. 이 경로가 스냅샷을 짓는 유일한 입구다.
     *
     * <p>부분 읽기(바뀐 장소만 다시 읽어 배열에 얹기)는 두지 않는다. 그 경로는 "얹을 기준 배열"이
     * 어느 시점의 것인지를 따로 관리해야 하고, 그 관리가 곧 이 구조에서 없앤 기준 경쟁이다.
     *
     * @param onObserved 이 읽기가 <b>어느 시점을 보게 됐는지</b>를 원본을 읽기 전에 알린다. 뒤에 온
     *                   쪽이 "이 비행에 붙어도 내가 본 최신이 담기나"를 판정하는 근거라, 반드시
     *                   read view가 열린 뒤(= 번호를 읽은 직후)에 불려야 한다
     */
    public SourceState readSourceState(Consumer<SnapshotMetadata> onObserved) {
        long startNanos = System.nanoTime();
        // ★ 트랜잭션 안에서는 행만 받는다. 객체·맵 구성은 아래 execute 밖에서 한다
        RawSource raw = readTransaction.execute(status -> {
            SnapshotMetadata observed = metadataRepository.readInCurrentTransaction();
            // ★ 원본을 읽기 "전"에 알린다. 전량 읽기가 끝난 뒤면 이 비행에 붙을지 판정할 쪽이
            //   그동안 읽은 번호가 비어 있는 것으로 보고 지나간다
            onObserved.accept(observed);
            return new RawSource(observed, readListSource(), readTagSource());
        });
        long readNanos = System.nanoTime() - startNanos;

        Source source = toSource(raw.listRows());
        Map<Long, TagView> tagViews = toTagViews(raw.tagRows());
        log.info("장소 목록 원본 읽기 완료 - revision={}, cursorVersion={}, places={}, tags={},"
                        + " 읽기={}ms, 조립 포함={}ms",
                raw.metadata().revision(), raw.metadata().cursorVersion(),
                source.entries().size(), tagViews.size(),
                Duration.ofNanos(readNanos).toMillis(),
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        return new SourceState(raw.metadata(), source.entries(), source.views(), tagViews);
    }

    /**
     * 지목한 장소들의 표시값과 그 관측 시점. 어드민 수정을 커밋 직후 메모리에 얹는 경로가 쓴다
     * ({@link SnapshotViewPatcher}).
     *
     * <p><b>결과에 없는 id는 그 장소가 목록 원본에서 사라졌다는 뜻이다</b>(삭제·비활성). 부르는
     * 쪽은 그것을 "표시값 없음"으로 다뤄 홀더에서 지운다 — 조회 경로가 표시값 없는 행을
     * 건너뛰므로 그것이 곧 목록에서 사라지는 것이다.
     *
     * <p>동네 하나를 되살리면 수백 건이 한꺼번에 들어온다. 그래서 id마다 부르지 않고 {@code IN}
     * 문장 하나로 받는다 — 장소마다 왕복하면 그 수만큼 쿼리가 나간다.
     */
    public ViewState readViews(Collection<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return new ViewState(readMetadata(), Map.of());
        }
        RawViews raw = readTransaction.execute(status -> new RawViews(
                metadataRepository.readInCurrentTransaction(), readViewRows(placeIds)));
        Map<Long, PlaceView> views = new HashMap<>(placeIds.size() * 2);
        for (Object[] row : raw.rows()) {
            PlaceView view = toView(row);
            views.put(view.placeId(), view);
        }
        return new ViewState(raw.metadata(), views);
    }

    /** 태그 표시값 전량과 그 관측 시점. 태그는 수백 건이라 바뀐 것만 골라 읽을 이유가 없다. */
    public TagViewState readTagViews() {
        RawTags raw = readTransaction.execute(status -> new RawTags(
                metadataRepository.readInCurrentTransaction(), readTagSource()));
        return new TagViewState(raw.metadata(), toTagViews(raw.rows()));
    }

    private SnapshotMetadata readMetadata() {
        return readTransaction.execute(status -> metadataRepository.readInCurrentTransaction());
    }

    /** 트랜잭션이 돌려주는 것 — 행 묶음과 번호뿐이고 도메인 객체는 없다 */
    private record RawSource(
            SnapshotMetadata metadata, List<Object[]> listRows, List<Object[]> tagRows) {}

    private record RawViews(SnapshotMetadata metadata, List<Object[]> rows) {}

    private record RawTags(SnapshotMetadata metadata, List<Object[]> rows) {}

    private record Source(List<PlaceEntry> entries, ConcurrentMap<Long, PlaceView> views) {}

    private Source toSource(List<Object[]> rows) {
        List<PlaceEntry> entries = new ArrayList<>(rows.size());
        ConcurrentMap<Long, PlaceView> views = new ConcurrentHashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            entries.add(toEntry(row));
            PlaceView view = toView(row);
            views.put(view.placeId(), view);
        }
        return new Source(entries, views);
    }

    private static Map<Long, TagView> toTagViews(List<Object[]> rows) {
        Map<Long, TagView> views = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            long tagId = ((Number) row[0]).longValue();
            views.put(tagId, new TagView(tagId, (String) row[1], toBoolean(row[2])));
        }
        return views;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readListSource() {
        return em.createNativeQuery(LIST_SOURCE_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readViewRows(Collection<Long> placeIds) {
        return em.createNativeQuery(SOME_VIEWS_SQL)
                .setParameter("placeIds", placeIds)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readTagSource() {
        return em.createNativeQuery(TAG_SOURCE_SQL).getResultList();
    }

    private static PlaceView toView(Object[] row) {
        return new PlaceView(
                ((Number) row[0]).longValue(),
                (String) row[10],
                (String) row[12],
                toNullableLong(row[11]));
    }

    private static PlaceEntry toEntry(Object[] row) {
        int ratingToInt = ((BigDecimal) row[7]).movePointRight(2).intValueExact();
        return new PlaceEntry(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).doubleValue(),
                toLocalDateTime(row[4]).toEpochSecond(ZoneOffset.UTC),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                ratingToInt,
                toNullableDouble(row[8]),
                toNullableDouble(row[9]));
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        return value instanceof LocalDateTime ldt ? ldt : ((Timestamp) value).toLocalDateTime();
    }

    private static Double toNullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Long toNullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }
}
