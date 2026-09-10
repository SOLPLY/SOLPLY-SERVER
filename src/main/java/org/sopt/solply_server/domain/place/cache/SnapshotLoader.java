package org.sopt.solply_server.domain.place.cache;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 목록 캐시 세 벌 — 회차 스냅샷({@link SnapshotBox}) · 장소 표시값({@link PlaceViewHolder}) ·
 * 태그 표시값({@link TagViewHolder}) — 을 짓는 <b>유일한</b> 곳. 전량 진입점은 {@link #rebuild()}
 * 하나이고, 그것을 부르는 것은 {@link SnapshotScheduler}(기동 한 번 · 10분 주기)와
 * {@link SnapshotRefresher}(어드민 커밋 뒤) 둘이다. 어드민 훅은 그 밖에
 * {@link #readView(long)}(장소 한 건)과 {@link #readTagViews()}(태그 전량)도 쓴다.
 *
 * <p><b>쿼리가 두 문장인 것이 이 클래스의 전부다.</b>
 * <ul>
 *   <li>문장 ①은 <b>장소당 한 행</b>이고 원천은 {@code place_stats} 하나다 — 목록에 나와도 되는
 *       장소 = place_stats에 행이 있는 장소라는 불변식이다. 순서 축뿐 아니라 표시값(이름·좌표·
 *       메인 태그 id·썸네일 파일 키)까지 같은 테이블의 칸이라 조인도 곁문장도 없다 (V40).
 *       한 행이 엔트리 하나와 {@link PlaceView} 하나로 갈라진다.</li>
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
 * <p><b>전량 읽기는 어노테이션이 아니라 {@link TransactionTemplate}으로 연다.</b>
 * {@link #rebuild()}는 락을 트랜잭션보다 <em>먼저</em> 잡아야 하는데(근거는
 * {@link CacheWriteLock}), 메서드에 {@code @Transactional}을 달면 프록시가 그 반대 순서를
 * 강제한다. 락 안에서 프록시를 다시 타려 해도 자기 호출이라 어노테이션이 조용히 무시되므로,
 * 템플릿을 직접 들고 여는 것이 유일하게 정확한 방법이다.
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
    private final SnapshotBox snapshotBox;
    private final PlaceViewHolder placeViewHolder;
    private final TagViewHolder tagViewHolder;
    private final CacheWriteLock writeLock;
    private final SnapshotVersionIssuer versionIssuer;
    /** 전량 읽기 두 문장을 담는 트랜잭션 — 어노테이션을 쓰지 않는 이유는 클래스 javadoc */
    private final TransactionTemplate readTransaction;

    public SnapshotLoader(
            EntityManager em,
            SnapshotBox snapshotBox,
            PlaceViewHolder placeViewHolder,
            TagViewHolder tagViewHolder,
            CacheWriteLock writeLock,
            SnapshotVersionIssuer versionIssuer,
            PlatformTransactionManager transactionManager) {
        this.em = em;
        this.snapshotBox = snapshotBox;
        this.placeViewHolder = placeViewHolder;
        this.tagViewHolder = tagViewHolder;
        this.writeLock = writeLock;
        this.versionIssuer = versionIssuer;
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
     * ({@code AdminPlaceService#activatePlacesByTownIds})는 행을 다시 짓고 전량 재빌드를 부르기
     * 때문이다.
     */
    private static final String SINGLE_VIEW_SQL = """
            SELECT ps.name,
                   ps.main_tag_id,
                   ps.thumbnail_file_key
            FROM place_stats ps
            WHERE ps.place_id = :placeId
            """;

    /**
     * 스냅샷과 표시값 두 벌을 통째로 다시 짓고 교체한다.
     *
     * <p><b>락이 트랜잭션보다 먼저다.</b> 재빌드와 표시값 패치가 한 줄로 서야 어드민 수정이
     * 유실되지 않고(근거는 {@link CacheWriteLock}), <b>락을 기다리는 동안 커넥션을 쥐고 있으면
     * 안 된다</b> — 어드민 둘이 동시에 커밋하면 기다리는 쪽이 커넥션을 잡은 채 잠들고, 락을 쥔 쪽은
     * 발급용 커넥션을 하나 더 요구해 풀이 얕을 때 서로를 굶긴다. 그래서 락을 먼저 잡고, 읽기
     * 트랜잭션은 락 안에서 열고 닫는다.
     *
     * <p><b>발급은 읽기 트랜잭션이 닫힌 뒤다.</b> 읽는 동안 발급하면 읽기 커넥션과 발급 커넥션을
     * 동시에 쥐지만, 순서를 이렇게 두면 락 안에서 쥐는 커넥션이 언제나 하나다.
     *
     * <p><b>버전은 여기서, 스냅샷을 완성한 순간에, 한 번만 발급한다.</b> 이것이 "버전↔내용 1:1"
     * 불변식의 근거다 — 남의 버전에 내 내용을 붙이는 경로가 존재하지 않으므로 "버전은 같은데 목록이
     * 다른" 사고가 구조로 봉쇄된다. 홀더({@link SnapshotBox#adopt})는 완성된 스냅샷을 받기만
     * 하고 버전을 찍지 않는다. 번호의 출처는 DB 발급 테이블이다
     * ({@link SnapshotVersionIssuer}) — 이 인스턴스 안의 단조는 락이 발급을 줄 세워 지켜지고,
     * 빌더가 둘 이상이 될 때의 단조는 발급소가 하나인 것이 지켜 준다.
     *
     * <p><b>계약 — 스냅샷 완성과 {@code adopt} 사이가 확장 설계의 삽입 지점이다.</b> 지금 그
     * 사이에 있는 것은 버전 발급 하나이고, 다중 인스턴스판의 아카이브 적재(Redis {@code SET})와
     * 발행({@code PUBLISH NEW_VERSION})이 발급 바로 뒤에 들어간다
     * ({@code docs/design/2026-09-01-multi-instance-snapshot-pipeline.md} §3-1·3-2).
     * 그 자리를 비워 두려고 버전 발급을 홀더에서 이리로 옮겼으니, 그 사이에 다른 관심사를
     * 끼워 넣지 말 것.
     *
     * <p><b>표시값 홀더를 스냅샷보다 먼저 교체한다.</b> 순서가 반대면 새 스냅샷에만 있는 장소가 옛 맵에
     * 없어 조회 경로가 그 행을 건너뛰는 창이 열린다. 먼저 교체하면 그 창이 없고, 남는 것은
     * "삭제된 장소를 옛 스냅샷에서 만나 건너뛰는" 계약상 정상 경로뿐이다. 버전 발급은 홀더 교체보다
     * 앞이다 — 발급이 실패하면 스냅샷도 홀더도 직전 회차 그대로 남아 "실패하면 아무것도 바뀌지
     * 않는다"가 성립한다.
     *
     * <p><b>아래 로그를 지우지 말 것.</b> 나중에 회차 직후 CPU 스파이크가 문제가 됐을 때
     * "몇 행을 몇 ms에 지었는가"가 남아 있지 않으면 원인을 이 경로로 좁힐 수 없다
     * (실측 선례: 6,320개 94~302ms). 버전도 함께 남긴다 — 만료를 호소하는 커서의 버전이 어느
     * 회차였는지는 이 로그 말고 대조할 곳이 없다.
     *
     * @return 스냅샷에 담긴 장소 수
     */
    public int rebuild() {
        return writeLock.call(this::rebuildInLock);
    }

    private int rebuildInLock() {
        long startNanos = System.nanoTime();

        Loaded loaded = readTransaction.execute(status -> new Loaded(readSource(), readTagViews()));
        Source source = loaded.source();
        Map<Long, TagView> tagViews = loaded.tagViews();

        SortedPlaces fresh = SortedPlaces.of(source.entries());
        // 발급은 자기 트랜잭션에서 돈다 — 읽기 트랜잭션은 이미 닫혔고 그것이 읽기 전용이라
        // INSERT를 실을 수 없었기 때문이기도 하다. 홀더 교체보다 앞에 두어, 발급이 실패하면
        // 스냅샷도 홀더도 직전 회차 그대로 남는다.
        Snapshot snapshot = new Snapshot(versionIssuer.issue(), fresh);
        placeViewHolder.replaceAll(source.views());
        tagViewHolder.replaceAll(tagViews);
        snapshotBox.adopt(snapshot);

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        log.info("장소 목록 스냅샷 교체 완료 - version={}, places={}, towns={}, arrays={}, tags={},"
                        + " elapsed={}ms",
                snapshot.version(), fresh.placeCount(), fresh.townCount(), fresh.arrayCount(),
                tagViews.size(), elapsedMs);
        return fresh.placeCount();
    }

    /** 읽기 트랜잭션 하나가 낳는 것 전부 — 이 record가 트랜잭션의 경계를 눈에 보이게 한다 */
    private record Loaded(Source source, Map<Long, TagView> tagViews) {}

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

    /**
     * 문장 ①의 행을 엔트리 목록과 표시값 맵으로 접는다. 부분 결과가 캐시로 새지 않는다 — 교체는
     * {@link #rebuildInLock()}이 이 메서드를 끝까지 받은 뒤 한 번뿐이다.
     */
    private Source readSource() {
        List<Object[]> rows = readListSource();
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
     * {@link #rebuildInLock()}은 자기 호출이라 어노테이션이 무시되고 이미 열린 읽기 트랜잭션
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
