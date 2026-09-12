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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 목록 캐시의 원천을 DB에서 읽는 <b>유일한</b> 곳. <b>읽기만 한다</b> — 힙 교체도 번호 발급도
 * 하지 않는다.
 *
 * <p><b>읽기와 설치를 가른 것이 이 클래스의 계약이다.</b> 예전에는 읽기·짓기·홀더 교체가 한
 * 몸이었고, 그러면 어드민 훅이 "공유 발행이 성공하기 전에는 로컬을 고치지 않는다"를 지킬 수
 * 없다. 힙에 설치하는 일은 전부 {@code SnapshotInstaller}에 있다.
 *
 * <p><b>읽는 방식이 둘이다.</b>
 * <ul>
 *   <li><b>전량</b>({@link #readSourceState()}) — 발행자가 원본에서 발행물을 지을 때.</li>
 *   <li><b>부분</b>({@link #readChangedState(Collection)}) — 어드민이 손댄 장소만. <b>어드민
 *       쓰기는 전량을 부르지 않는다</b> — 장소 하나를 고치자고 전 장소를 다시 읽는 것이 이 경로가
 *       없애려는 비용 그 자체다.</li>
 * </ul>
 * 어드민 훅은 그 밖에 {@link #readView(long)}(장소 한 건)과 {@link #readTagViews()}(태그 전량)도 쓴다.
 *
 * <p><b>쿼리가 두 문장인 것이 이 클래스의 전부다.</b>
 * <ul>
 *   <li>문장 ①은 <b>장소당 한 행</b>이고 원천은 {@code place_stats} 하나다 — 목록에 나와도 되는
 *       장소 = place_stats에 행이 있는 장소라는 불변식이다. 순서 축뿐 아니라 표시값(이름·좌표·
 *       메인 태그 id·썸네일 파일 키)까지 같은 테이블의 칸이라 조인도 곁문장도 없다 (V40).
 *       한 행이 엔트리 하나와 {@link PlaceView} 하나로 갈라진다. 부분 패치는 <b>같은 문장에
 *       {@code WHERE place_id IN (…)}만 붙여</b> 읽는다({@link #CHANGED_SOURCE_SQL}) — 두 경로가
 *       한 SELECT 목록을 공유하므로 "패치된 뒤"와 "다음 회차 뒤"의 값이 갈릴 자리가 없다.</li>
 *   <li>문장 ③은 태그 전량이다. 수십 행이라 조건을 걸 값어치가 없고, <b>비활성 태그도 담아야</b>
 *       한다 — 대표 태그 이름을 비우는 판정이 조회 시점에 {@code active}로 이뤄지기 때문이다
 *       ({@link TagView}). 번호가 ③인 것은 옛 문장 ②(썸네일 전량)가 V40으로 사라졌기 때문이고,
 *       다른 문서·주석이 그 번호로 이 문장을 가리킨다.</li>
 * </ul>
 *
 * <p><b>JPA를 쓰지 않는 것이 핵심 결정이다.</b> 엔티티로 전량을 읽으면 없애려는 하이드레이션 비용을
 * 배치에서 그대로 다시 치른다 — 조회 경로에서 덜어낸 CPU가 배치로 옮겨갈 뿐이다. {@code Object[]}만
 * 받아 자바에서 record로 접는다.
 *
 * <p><b>세 문장 모두 결과를 통째로 받아 접는다.</b> 10배 규모까지는 행 단위 streaming과 자원 차이가
 * 측정되지 않아({@code docs/perf/2026-09-10-rebuild-headroom-x10.md}) 단순한 쪽을 둔다. 재빌드의
 * 할당·CPU가 부담이 되면 {@code docs/design/2026-09-09-rebuild-streaming.md}의 streaming이 첫
 * 손잡이다.
 *
 * <p><b>읽기를 트랜잭션 하나로 묶는 이유는 두 문장이 같은 일관 읽기를 보게 하기 위해서다.</b>
 * 트랜잭션이 없으면 문장마다 커넥션이 갈려, 그 사이에 커밋된 태그 변경이 장소 목록과 어긋난
 * 조합으로 실릴 수 있다. <b>두 문장이 같은 Read View를 보는 근거는 격리 수준이 REPEATABLE READ인
 * 것이지 {@code readOnly}가 아니다</b> — {@code readOnly}는 쓰기를 막는 힌트일 뿐 스냅샷을 고정하지
 * 않는다. 격리 수준을 코드에서 고정하지 않으므로 이 성질은 <b>운영 DB의 기본값</b>(MySQL InnoDB
 * 기본 REPEATABLE READ)에 기대고 있다. 기본값을 READ COMMITTED로 내리는 날 이 문단부터 짚을 것.
 *
 * <p><b>{@code REQUIRES_NEW}인 이유는 어드민 훅 때문이다.</b> 어드민 쓰기의 재생성은 커밋
 * <em>뒤에</em> 도는데({@code TransactionSynchronization#afterCommit}), 그 시점에는 이미 끝난
 * 트랜잭션의 자원이 아직 스레드에 묶여 있다. 전파를 기본값으로 두면 이 문장들이 <b>이미 커밋된
 * 트랜잭션에 참여</b>하는 모양이 되므로, 새 트랜잭션을 명시적으로 연다. 트랜잭션이 없는 다른
 * 호출자(스케줄러)에게는 그냥 새 트랜잭션 하나를 여는 것과 같아 달라지는 것이 없다.
 *
 * <p><b>읽기는 어노테이션이 아니라 {@link TransactionTemplate}으로 연다.</b> 어드민 훅은
 * {@link CacheWriteLock}을 트랜잭션보다 <em>먼저</em> 잡은 채로 이 메서드들을 부르는데, 메서드에
 * {@code @Transactional}을 달면 프록시가 그 반대 순서를 강제한다. 락 안에서 프록시를 다시 타려
 * 해도 자기 호출이라 어노테이션이 조용히 무시되므로, 템플릿을 직접 들고 여는 것이 유일하게
 * 정확한 방법이다.
 *
 * <p><b>동치 계약 — 응답이 바뀌면 안 된다.</b> 표시값은 엔티티 경로와 같은 값을 내야 한다.
 * <ul>
 *   <li>썸네일: {@code Place.getThumbnailFileKey()}가 {@code @OrderBy("displayOrder ASC")} +
 *       {@code findFirst()}이므로, 규칙도 {@code display_order ASC}의 첫 행이다
 *       (MySQL·하이버네이트 모두 ASC에서 NULL이 앞이라 정렬 결과가 같다). 그 규칙은 쓰기 문장
 *       ({@code PlaceStatsRepository})이 {@code place_stats.thumbnail_file_key}에 고정해 두고
 *       로더는 읽기만 한다. 담는 것은 그 행의 <b>파일 키 원값</b>이고 URL 결합은 조회 경로의
 *       몫이다({@link PlaceView}).</li>
 *   <li>메인 태그: 첫 MAIN 태그의 <b>id</b>를 활성 여부와 무관하게 담는다. 그 규칙은 쓰기 문장
 *       ({@code PlaceStatsRepository})이 {@code place_stats.main_tag_id}에 고정해 두고 로더는
 *       읽기만 한다 — 활성으로 거르면 비활성 MAIN이 붙은 장소에서 <em>다음</em> MAIN 태그가 뽑혀
 *       엔티티 경로와 갈린다({@link PlaceView}).</li>
 * </ul>
 */
@Slf4j
@Component
public class SnapshotLoader {

    private final EntityManager em;
    /** 전량·부분 읽기를 담는 트랜잭션 — 어노테이션을 쓰지 않는 이유는 클래스 javadoc */
    private final TransactionTemplate readTransaction;

    public SnapshotLoader(EntityManager em, PlatformTransactionManager transactionManager) {
        this.em = em;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * 장소당 한 행 — 정렬 축 다섯 + 좌표 + 이름 + 메인 태그 id + 썸네일 파일 키. <b>SELECT 목록이 곧
     * {@link PlaceEntry}와 {@link PlaceView}의 필드 목록</b>이라, 축이나 표시 필드를 늘릴 때
     * 두 곳이 함께 움직인다.
     *
     * <p>활성 조건이 없다 — 있으면 안 된다. 행의 존재를 정하는 주체는 {@code place_stats} 하나이고,
     * 그것이 DB 경로와 같은 행 집합을 보장하는 근거다
     * ({@code PlaceListDbQueryRepository} javadoc의 불변식).
     *
     * <p><b>조인도 ORDER BY도 없다.</b> 표시값 넷(이름·좌표·메인 태그 id·썸네일 파일 키)이
     * {@code place_stats}의 칸이 된 뒤로 붙일 테이블이 없고(V40), 장소당 한 행은 PK가 보장하므로
     * 행을 접기 위해 정렬에 기댈 이유도 없다. 메인 태그와 썸네일을 고르는 규칙은 이 문장이 아니라
     * 그 칸을 채우는 쓰기 문장({@code PlaceStatsRepository})에 있다.
     */
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

    /**
     * 부분 패치가 읽는 문장 — <b>문장 ①에 {@code WHERE}만 붙인 것</b>이다. SELECT 목록을 손으로 한 벌
     * 더 적지 않는 것이 요점이다: 목록이 갈리면 같은 장소가 "패치된 뒤"와 "다음 회차 뒤"에 다른 값을
     * 갖는데, 그 어긋남은 아무 오류도 내지 않는다.
     *
     * <p><b>없는 행은 없는 채로 돌아온다.</b> 삭제·비활성으로 {@code place_stats} 행이 사라진 장소는
     * 결과에 끼지 않고, 그 부재가 곧 "배열에서 빼라"는 신호다 ({@link SortedPlaces#patch}).
     */
    private static final String CHANGED_SOURCE_SQL = LIST_SOURCE_SQL + """
            WHERE ps.place_id IN (:placeIds)
            """;

    /** 태그 전량 — 수십 행이라 조건을 걸지 않는다. 비활성도 담는 이유는 {@link TagView} */
    private static final String TAG_SOURCE_SQL = """
            SELECT t.id, t.name, t.active
            FROM tags t
            """;

    /**
     * 장소 하나의 표시값. <b>전량과 같은 원천을 읽어야 두 경로가 갈리지 않는다</b> — 표시값 셋 모두
     * {@link #LIST_SOURCE_SQL}과 똑같이 {@code place_stats}의 칸이라, 이 문장과 전량 문장 사이에
     * 규칙이 갈릴 자리 자체가 없다 (V40).
     *
     * <p>기준 테이블이 {@code place_stats}라 <b>비활성 장소는 행이 없어 패치가 no-op이 된다</b>.
     * 그래도 되는 이유는 비활성 장소가 정렬 배열에 없어 화면에 닿지 않고, 되살리는 경로
     * ({@code AdminPlaceService#activatePlacesByTownIds})는 행을 다시 짓고 그 장소들을
     * {@link #readChangedState(Collection)}로 넘겨 배열에 다시 세우기 때문이다.
     */
    private static final String SINGLE_VIEW_SQL = """
            SELECT ps.name,
                   ps.main_tag_id,
                   ps.thumbnail_file_key
            FROM place_stats ps
            WHERE ps.place_id = :placeId
            """;

    /**
     * 원본에서 읽은 한 벌 — <b>힙을 건드리지 않고 번호도 발급하지 않는다.</b> 발행자가 이것으로
     * payload를 만든다.
     *
     * <p><b>읽기와 설치를 가른 것이 이 클래스의 계약이다.</b> 예전에는 읽기·짓기·홀더 교체가 한
     * 몸이었는데, 그러면 어드민 훅이 "공유 발행이 성공하기 전에는 로컬을 고치지 않는다"를 지킬 수
     * 없다. 힙 교체는 {@code SnapshotInstaller} 한 곳으로 모았다.
     *
     * <p><b>아래 로그를 지우지 말 것.</b> 나중에 회차 직후 CPU 스파이크가 문제가 됐을 때
     * "몇 행을 몇 ms에 읽었는가"가 남아 있지 않으면 원인을 이 경로로 좁힐 수 없다
     * (실측 선례: 6,320개 94~302ms).
     */
    public SourceState readSourceState() {
        long startNanos = System.nanoTime();
        Loaded loaded = readTransaction.execute(status -> new Loaded(readSource(), readTagViews()));
        Source source = loaded.source();
        log.info("장소 목록 원본 읽기 완료 - places={}, tags={}, elapsed={}ms",
                source.entries().size(), loaded.tagViews().size(),
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        return new SourceState(source.entries(), source.views(), loaded.tagViews());
    }

    /**
     * 어드민이 손댄 장소들만 다시 읽는다. <b>읽어 오는 것은 행 전체지만 그중 무엇을 쓸지는
     * {@link SortedPlaces#patch}가 정한다</b> — 로더의 몫은 DB에 지금 무엇이 있는가를 전하는
     * 것까지다(근거는 {@link PlaceEntry#patchedBy}).
     *
     * <p>행이 사라진 장소는 결과에 끼지 않는다. 부른 쪽이 그것을 "배열에서 뺀다"로 읽는다.
     *
     * @param changedPlaceIds 어드민 트랜잭션이 손댄 장소 id — 중복은 호출자가 이미 접었다
     *                        ({@link SnapshotRefresher})
     */
    public SourceState readChangedState(Collection<Long> changedPlaceIds) {
        Source source = readTransaction.execute(status -> readChangedSource(changedPlaceIds));
        return new SourceState(source.entries(), source.views(), Map.of());
    }

    /** 읽기 트랜잭션 하나가 낳는 것 전부 — 이 record가 트랜잭션의 경계를 눈에 보이게 한다 */
    private record Loaded(Source source, Map<Long, TagView> tagViews) {}

    /**
     * 원본 한 벌. {@code tagViews}는 {@link #readChangedState}에서 비어 있다 — 부분 읽기는 태그를
     * 다시 읽지 않는다.
     */
    public record SourceState(
            List<PlaceEntry> entries,
            Map<Long, PlaceView> views,
            Map<Long, TagView> tagViews) {
    }


    /**
     * 장소 하나의 표시값을 다시 읽는다. 어드민이 이름·이미지·메인 태그를 고친 뒤 그 항목만 갈아
     * 끼우는 경로가 이것이다 ({@link SnapshotRefresher#patchPlaceViewAfterCommit}).
     *
     * <p><b>규칙은 전량 재빌드와 같아야 한다.</b> 갈리면 같은 장소가 "패치된 뒤"와 "다음 회차 뒤"에
     * 다르게 보인다 — 재빌드 결과와 패치 결과의 동치는 IT가 지킨다.
     *
     * <p>여기는 {@code @Transactional}을 그대로 둔다 — 호출자({@link SnapshotRefresher})가
     * <b>락 안에서</b> 부르므로 순서가 이미 "락 → 트랜잭션"이고, 트랜잭션이 한 겹뿐이라 락을 기다리며
     * 커넥션을 쥐는 창도 없다.
     *
     * @return 장소 행이 없으면 {@code empty} — 호출자는 맵을 건드리지 않는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PlaceView> readView(long placeId) {
        List<Object[]> rows = readSingleViewRow(placeId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new PlaceView(
                placeId,
                (String) row[0],
                (String) row[2],
                toNullableLong(row[1])));
    }

    /**
     * 문장 ①이 한 번에 낳는 두 벌 — 순서 값과 표시값이다.
     *
     * <p>표시값 맵이 {@link ConcurrentMap}인 것은 이것이 그대로 {@link PlaceViewHolder}의 맵이 되기
     * 때문이다 — 홀더는 복사하지 않고 참조만 받고, 그 뒤로 어드민 패치가 항목을 고친다.
     */
    private record Source(List<PlaceEntry> entries, ConcurrentMap<Long, PlaceView> views) {}

    /** 문장 ①의 전량 */
    private Source readSource() {
        return toSource(readListSource());
    }

    /** 문장 ①에 id 조건만 붙인 것 — 접는 방식이 전량과 <b>같은 메서드</b>다 */
    private Source readChangedSource(Collection<Long> placeIds) {
        return toSource(readChangedRows(placeIds));
    }

    /**
     * 문장 ①의 행을 엔트리 목록과 표시값 맵으로 접는다. 부분 결과가 캐시로 새지 않는다 — 이
     * 클래스는 홀더를 아예 들고 있지 않다.
     */
    private Source toSource(List<Object[]> rows) {
        List<PlaceEntry> entries = new ArrayList<>(rows.size());
        // 홀더가 그대로 받아 쓰는 맵이라 여기서 처음부터 동시 수정 가능한 것으로 만든다
        ConcurrentMap<Long, PlaceView> views = new ConcurrentHashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            long placeId = ((Number) row[0]).longValue();
            entries.add(toEntry(row));
            views.put(placeId, new PlaceView(
                    placeId,
                    (String) row[10],
                    (String) row[12],
                    toNullableLong(row[11])));
        }
        return new Source(entries, views);
    }

    /**
     * 태그 표시값 전량. 재빌드가 쓰는 것과 <b>같은 메서드</b>를 어드민 태그 훅도 쓴다
     * ({@link SnapshotRefresher#refreshTagViewsAfterCommit}) — 태그는 수십 행이라 어느
     * 것이 바뀌었는지 모아 단건으로 읽을 값어치가 없고, 규칙이 하나면 두 경로가 갈릴 자리도 없다.
     *
     * <p>{@code @Transactional}은 훅에서 들어오는 <b>바깥 호출</b>을 위한 것이다.
     * {@link #readSourceState()}는 자기 호출이라 어노테이션이 무시되고 이미 열린 읽기 트랜잭션
     * 안에서 도는데, 그것이 의도한 모양이다 — 두 문장이 한 트랜잭션을 함께 쓴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Map<Long, TagView> readTagViews() {
        List<Object[]> rows = readTagSource();
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

    /**
     * id 목록은 <b>바인드 파라미터</b>로 넘긴다 — 값을 SQL 문자열에 끼워 넣으면 손댄 장소 수마다
     * 다른 문장이 생겨 DB의 문장 캐시가 그만큼 갈린다.
     */
    @SuppressWarnings("unchecked")
    private List<Object[]> readChangedRows(Collection<Long> placeIds) {
        return em.createNativeQuery(CHANGED_SOURCE_SQL)
                .setParameter("placeIds", placeIds)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readTagSource() {
        return em.createNativeQuery(TAG_SOURCE_SQL).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> readSingleViewRow(long placeId) {
        return em.createNativeQuery(SINGLE_VIEW_SQL)
                .setParameter("placeId", placeId)
                .getResultList();
    }

    /**
     * <b>값의 좁힘이 여기서 한 번만 일어난다.</b> 생성일은 커서와 같은 식으로 epoch 초가 되고
     * (근거는 {@code PlaceListDbQueryRepository#findLatestRows}의 왕복 계약), 평점은 DECIMAL(3,2)의
     * 무척도 정수만 {@code ratingToInt}으로 든다 ({@link PlaceEntry} javadoc).
     *
     * <p>{@code intValueExact}는 의도다 — 컬럼 스케일이 2를 <b>넘는</b> 날 여기서 터진다(정보가 상하는
     * 쪽만 막는다). 그 자리 수가 상수라는 것이 {@code ratingToInt}의 전제이므로, 전제가 깨지면 조용히
     * 값을 버리는 대신 멈춰야 한다.
     */
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

    /**
     * DATETIME 컬럼의 반환 타입은 드라이버·하이버네이트 조합에 따라 {@code Timestamp}와
     * {@code LocalDateTime}으로 갈린다. 어느 쪽이든 <b>벽시계 값 그대로</b> 받아야 한다 —
     * 여기서 타임존 변환이 끼면 커서 초가 DB 경로의 것과 어긋나 페이징이 방식마다 달라진다
     * ({@code PlaceListDbQueryRepository#toLocalDateTime}과 같은 이유).
     */
    private static LocalDateTime toLocalDateTime(Object value) {
        return value instanceof LocalDateTime ldt ? ldt : ((Timestamp) value).toLocalDateTime();
    }

    /** 좌표는 NULL일 수 있다 — 0으로 채우면 기니만 앞바다가 실재 좌표라 "좌표 없음"과 섞인다 */
    private static Double toNullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    /** MAIN 태그가 없는 장소는 NULL이다 — 0으로 채우면 존재하지 않는 태그를 가리킨다 */
    private static Long toNullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /** BOOLEAN 컬럼도 드라이버에 따라 {@code Boolean}과 {@code TINYINT}로 갈린다 */
    private static boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }
}
