package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.domain.place.cache.SnapshotRebuilder;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.service.PlaceListRequestOrchestrator;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동네 캐시 경로의 요청 사슬 — <b>번호 관측 → 커서 비교 → 적재 → 페이지 → 다음 커서</b>까지 걷는다.
 *
 * <p>여기서 무는 계약들.
 * <ul>
 *   <li>정적 5축의 결과가 기존 전역 경로와 <b>같다</b>.
 *   <li>커서 페이징이 항목을 흘리지도 겹치지도 않는다.
 *   <li>번호가 올라도 커서가 선 번호가 캐시에 있으면 <b>그 번호로 이어 간다</b>.
 *   <li>커서가 선 번호가 캐시에서 사라졌어도 <b>지금도 최신이면 같은 번호로 다시 적재</b>해
 *       이어 간다. 최신이 아니면 <b>명시 만료</b>다 — 최신으로 갈아타지 않는다.
 *   <li>첫 페이지는 축출 뒤에도 같은 번호로 다시 적재해 이어간다.
 *   <li>leaf 집합이 달라지면 만료다.
 *   <li>표시값은 다음 성공한 정기 전체 배치 뒤 재적재될 때 갱신된다.
 * </ul>
 */
@SpringBootTest
class TownCacheListFlowIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void townCacheProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("solply.place-list.list-source", () -> "TOWN_PRESORTED");
    }

    private static final String TOWN_NAME_PREFIX = "동네캐시IT동네";
    private static final String USER_NICKNAME_PREFIX = "동네캐시IT유저";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 21, 2, 0);
    private static final LocalDateTime CREATED_AT = CALCULATED_AT.minusDays(1);

    private static final List<PlaceSortType> STATIC_SORTS = List.of(
            PlaceSortType.POPULAR, PlaceSortType.LATEST, PlaceSortType.RATING,
            PlaceSortType.REVIEW_COUNT, PlaceSortType.BOOKMARK_COUNT);

    @Autowired private PlaceListRequestOrchestrator orchestrator;
    @Autowired private PlaceService placeService;
    @Autowired private TownPlacesCache townPlacesCache;
    @Autowired private TownVersionRepository townVersionRepository;
    @Autowired private TownVersionService townVersionService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private SnapshotInstaller snapshotInstaller;
    @Autowired private SnapshotMetadataRepository snapshotMetadataRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private SnapshotRebuilder snapshotRebuilder;

    private long parentTown;
    private long childTown;
    private long place1;
    private long place2;
    private long place3;
    private long me;

    @BeforeEach
    void setUp() {
        snapshotRebuilder = new SnapshotRebuilder(
                snapshotInstaller, snapshotMetadataRepository, transactionManager);

        parentTown = createTown(TOWN_NAME_PREFIX + "부모", null, true);
        // ⚠️ 자식을 <b>비활성</b>으로 둔다. 활성 자식이 하나라도 있으면 부모 요청의 leaf가
        //    {자식}으로 풀려(TownHierarchyResolver) 부모에 심은 장소가 통째로 대상에서 빠진다.
        //    leaf 집합이 달라지는 시나리오만 이 값을 뒤집는다.
        childTown = createTown(TOWN_NAME_PREFIX + "자식", parentTown, false);
        me = createUser();

        // 다섯 축의 순서가 서로 다르게 나오도록 값을 갈라 둔다 — 같은 순서를 내는 픽스처에서는
        // 정렬 분기가 통째로 뒤바뀌어도 전부 그린이다
        place1 = createPlace(parentTown, "동네캐시1", CREATED_AT, 3.0, 10L, 2L, 500);
        place2 = createPlace(parentTown, "동네캐시2", CREATED_AT.plusHours(1), 1.0, 1L, 9L, 300);
        place3 = createPlace(parentTown, "동네캐시3", CREATED_AT.plusHours(2), 9.0, 5L, 5L, 400);

        bump(List.of(parentTown, childTown));
        townPlacesCache.invalidateAll();
    }

    // === 기본 조회 ===

    @Test
    void 정적_다섯_축이_각각_자기_순서를_낸다() {
        assertThat(ids(get(PlaceSortType.POPULAR, null, 10)))
                .containsExactly(place3, place1, place2);       // 9.0 > 3.0 > 1.0
        assertThat(ids(get(PlaceSortType.LATEST, null, 10)))
                .containsExactly(place3, place2, place1);
        assertThat(ids(get(PlaceSortType.RATING, null, 10)))
                .containsExactly(place1, place3, place2);       // 5.00 > 4.00 > 3.00
        assertThat(ids(get(PlaceSortType.REVIEW_COUNT, null, 10)))
                .containsExactly(place2, place3, place1);       // 9 > 5 > 2
        assertThat(ids(get(PlaceSortType.BOOKMARK_COUNT, null, 10)))
                .containsExactly(place1, place3, place2);       // 10 > 5 > 1
    }

    /**
     * <b>기존 전역 경로와 같은 답을 내야 한다.</b> 캐시 구조를 바꾼 것이지 정렬 계약을 바꾼 것이
     * 아니다 — 순서의 정본은 {@code PlaceOrder} 하나다.
     */
    @Test
    void 다섯_축_모두_전역_경로와_같은_순서를_낸다() {
        snapshotRebuilder.rebuildAndInstall();

        for (PlaceSortType sort : STATIC_SORTS) {
            List<Long> viaTownCache = ids(get(sort, null, 10));
            List<Long> viaGlobal = ids(placeService.getPlaces(me, request(sort, null, 10)));

            assertThat(viaTownCache).as("정렬 %s", sort).isEqualTo(viaGlobal);
        }
    }

    @Test
    void 커서_페이징은_항목을_흘리지도_겹치지도_않는다() {
        PlaceFilterGetResponse page1 = get(PlaceSortType.POPULAR, null, 2);
        assertThat(ids(page1)).containsExactly(place3, place1);
        assertThat(page1.nextCursor()).isNotNull();

        PlaceFilterGetResponse page2 = get(PlaceSortType.POPULAR, page1.nextCursor(), 2);
        assertThat(ids(page2)).containsExactly(place2);
        assertThat(page2.nextCursor()).isNull();
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));
    }

    /** 다음 커서에는 <b>실제로 서빙한 번호</b>가 실린다. */
    @Test
    void 다음_커서는_서빙한_번호를_싣는다() {
        PlaceFilterGetResponse page1 = get(PlaceSortType.POPULAR, null, 2);

        PlaceListCursor cursor = PlaceListCursor.decode(page1.nextCursor());
        TownVersions carried = TownVersions.parse(cursor.scope());

        assertThat(carried.versionOf(parentTown)).isEqualTo(versionOf(parentTown));
    }

    @Test
    void 장소가_없는_동네는_빈_페이지를_낸다() {
        PlaceFilterGetResponse empty = get(childTown, PlaceSortType.LATEST, null, 10);

        assertThat(empty.places()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
    }

    // === 만료 ===

    /**
     * <b>번호가 올라도 보던 목록은 그대로 이어 간다.</b> 커서가 선 번호가 아직 캐시에 있으면
     * 그것으로 답한다 — DB 최신 번호가 달라졌다는 사실 자체는 만료 사유가 아니다.
     *
     * <p>새 번호에서만 보이는 장소를 하나 심어 <b>이어 간 페이지가 옛 집합</b>임을 확인한다.
     * 최신으로 갈아탔다면 그 장소가 2페이지에 끼어든다.
     */
    @Test
    void 번호가_올라도_옛_커서는_같은_목록을_이어_간다() {
        PlaceFilterGetResponse page1 = get(PlaceSortType.POPULAR, null, 2);
        assertThat(ids(page1)).containsExactly(place3, place1);
        long servedVersion = versionOf(parentTown);

        // 인기 점수를 가장 낮게 줘 새 번호의 목록에서는 맨 뒤에 붙는다
        createPlace(parentTown, "동네캐시신규", CREATED_AT, 0.5, 0L, 0L, 100);
        bump(List.of(parentTown));
        assertThat(versionOf(parentTown)).isGreaterThan(servedVersion);

        PlaceFilterGetResponse page2 = get(PlaceSortType.POPULAR, page1.nextCursor(), 5);

        assertThat(ids(page2)).as("옛 번호의 집합 그대로").containsExactly(place2);
    }

    /**
     * <b>커서가 선 번호가 캐시에서 사라졌어도 지금도 최신이면 다시 적재해 이어 간다.</b> 원본
     * 테이블의 현재 상태가 곧 그 번호의 데이터라 같은 목록을 다시 만들 수 있다.
     */
    @Test
    void 커서가_선_번호가_사라져도_최신이면_다시_적재해_이어_간다() {
        PlaceFilterGetResponse page1 = get(PlaceSortType.POPULAR, null, 2);
        long servedVersion = versionOf(parentTown);

        townPlacesCache.invalidateAll();     // 축출·재기동·다른 인스턴스와 같은 상태

        assertThat(ids(get(PlaceSortType.POPULAR, page1.nextCursor(), 2)))
                .containsExactly(place2);
        assertThat(townPlacesCache.get(parentTown, servedVersion)).isNotNull();
    }

    /**
     * <b>커서가 선 번호가 캐시에서 사라졌고 최신도 아니면 만료다.</b> 원본 테이블에는 지금 상태만
     * 있어 그 번호를 다시 만들어 낼 방법이 없고, 최신 번호로 슬쩍 대신 답하면 커서 좌표가 다른
     * 좌표계에서 해석돼 항목이 흘리거나 겹친다 — 200이라 클라이언트가 알 방법이 없다.
     */
    @Test
    void 커서가_선_번호가_사라지고_최신도_아니면_만료다() {
        PlaceFilterGetResponse page1 = get(PlaceSortType.POPULAR, null, 2);

        bump(List.of(parentTown));
        townPlacesCache.invalidateAll();     // 축출·재기동·다른 인스턴스와 같은 상태

        assertThatThrownBy(() -> get(PlaceSortType.POPULAR, page1.nextCursor(), 2))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);

        // 커서 없는 재요청은 막히지 않는다 — 클라이언트의 복구 경로가 살아 있다
        assertThat(ids(get(PlaceSortType.POPULAR, null, 10)))
                .containsExactly(place3, place1, place2);
    }

    /**
     * <b>옛 번호와 새 번호가 캐시에 나란히 산다.</b> 첫 페이지를 다시 받아 새 번호를 적재해도 옛
     * 번호의 스크롤이 계속된다는 것이, 저장소가 번호마다 자리를 따로 둔다는 사실의 모습이다.
     */
    @Test
    void 새_번호를_적재해도_옛_번호의_스크롤이_이어진다() {
        PlaceFilterGetResponse page1 = get(PlaceSortType.POPULAR, null, 2);
        long oldVersion = versionOf(parentTown);

        bump(List.of(parentTown));
        get(PlaceSortType.POPULAR, null, 10);        // 새 번호를 적재한다
        long newVersion = versionOf(parentTown);

        assertThat(townPlacesCache.get(parentTown, oldVersion)).isNotNull();
        assertThat(townPlacesCache.get(parentTown, newVersion)).isNotNull();
        assertThat(ids(get(PlaceSortType.POPULAR, page1.nextCursor(), 2)))
                .containsExactly(place2);
    }

    /** 같은 말을 한 번 더 — 축출 뒤 재적재가 <b>같은 번호</b>로 돌아온다. */
    @Test
    void 축출된_항목은_같은_번호로_다시_적재된다() {
        long version = versionOf(parentTown);
        get(PlaceSortType.POPULAR, null, 10);
        assertThat(townPlacesCache.get(parentTown, version)).isNotNull();

        townPlacesCache.invalidateAll();
        get(PlaceSortType.POPULAR, null, 10);

        assertThat(townPlacesCache.get(parentTown, version)).isNotNull();
    }

    /**
     * <b>leaf 집합이 달라지면 만료다.</b> 같은 {@code townId} 요청인데 풀어 낸 동네가 달라지면
     * 탐색 대상이 조용히 바뀐다 — 번호만 비교해서는 잡히지 않는 경우다.
     */
    @Test
    void 하위_동네가_활성화되면_옛_커서는_만료다() {
        // 자식이 비활성이라 leaf = {부모}
        String cursor = get(PlaceSortType.POPULAR, null, 2).nextCursor();
        assertThat(TownVersions.parse(PlaceListCursor.decode(cursor).scope()).townIds())
                .containsExactly(parentTown);

        activate(childTown);    // leaf = {자식}

        assertThatThrownBy(() -> get(PlaceSortType.POPULAR, cursor, 2))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);
    }

    /**
     * <b>여러 동네를 걸친 탐색에서 이동한 장소가 중복되지 않는다.</b> 이동 전 A와 이동 후 B를
     * 섞어 보면 같은 장소가 두 번 나온다 — 두 동네가 <b>같은 시점</b>으로 확보돼야 한다는 계약이
     * 지켜지지 않았을 때 나오는 모습이다.
     */
    @Test
    void 두_동네에_걸친_탐색에서_이동한_장소가_중복되지_않는다() {
        long leftTown = createTown(TOWN_NAME_PREFIX + "좌", parentTown, true);
        long rightTown = createTown(TOWN_NAME_PREFIX + "우", parentTown, true);
        long wanderer = createPlace(leftTown, "동네캐시이동", CREATED_AT, 5.0, 1L, 1L, 400);
        bump(List.of(leftTown, rightTown));
        townPlacesCache.invalidateAll();

        // leaf = {좌, 우} — 부모 요청이 두 동네의 합집합을 본다
        assertThat(ids(get(parentTown, PlaceSortType.LATEST, null, 10)))
                .containsExactly(wanderer);

        moveTown(wanderer, rightTown);
        bump(List.of(leftTown, rightTown));
        townPlacesCache.invalidateAll();

        assertThat(ids(get(parentTown, PlaceSortType.LATEST, null, 10)))
                .as("한 번만 나온다")
                .containsExactly(wanderer);
    }

    // === 표시값 ===

    /**
     * <b>표시값만 바뀐 변경은 탐색을 끊지 않되, 목록에는 바로 나타나지도 않는다.</b> 상한은 다음
     * 성공한 정기 전체 배치이고, 그 배치가 전 동네를 올리면 재적재가 새 값을 싣는다.
     */
    @Test
    void 표시값만_바뀐_변경은_배치_뒤_재적재에서_나타난다() {
        assertThat(nameOf(get(PlaceSortType.POPULAR, null, 10), place3)).isEqualTo("동네캐시3");

        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE place_stats SET name = ? WHERE place_id = ?", "이름바뀜", place3));

        // 번호가 그대로라 캐시가 그대로 답한다 — 지연을 허용한다는 계약이 여기다
        assertThat(nameOf(get(PlaceSortType.POPULAR, null, 10), place3)).isEqualTo("동네캐시3");

        batchProcessor.recalculateCounts(CALCULATED_AT);

        assertThat(nameOf(get(PlaceSortType.POPULAR, null, 10), place3)).isEqualTo("이름바뀜");
    }

    /**
     * <b>표시값을 못 찾아 행이 빠지는 경로가 없다.</b> 동네 객체가 정렬 값과 표시값을 한 몸으로
     * 들고 있어서, "홀더에 없다"는 상태 자체가 생기지 않는다.
     */
    @Test
    void 전역_표시_홀더가_비어_있어도_행이_빠지지_않는다() {
        // 전역 홀더를 비운 상태를 만든다 — 전역 스냅샷을 한 번도 짓지 않은 인스턴스와 같다
        townPlacesCache.invalidateAll();

        PlaceFilterGetResponse response = get(PlaceSortType.POPULAR, null, 10);

        assertThat(ids(response)).containsExactly(place3, place1, place2);
        assertThat(response.places()).allMatch(preview -> preview.placeName() != null);
    }

    // === 픽스처 ===

    private PlaceFilterGetResponse get(PlaceSortType sort, String cursor, Integer size) {
        return get(parentTown, sort, cursor, size);
    }

    private PlaceFilterGetResponse get(long town, PlaceSortType sort, String cursor, Integer size) {
        try {
            return orchestrator.getPlaces(me, request(town, sort, cursor, size))
                    .get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PlaceFilterGetRequest request(PlaceSortType sort, String cursor, Integer size) {
        return request(parentTown, sort, cursor, size);
    }

    private static PlaceFilterGetRequest request(
            long town, PlaceSortType sort, String cursor, Integer size) {
        return new PlaceFilterGetRequest(
                town, false, null, null, null, sort, cursor, size, null, null);
    }

    private void bump(List<Long> townIds) {
        transactionTemplate.executeWithoutResult(
                status -> townVersionService.markTownsChanged(townIds));
    }

    private long versionOf(long townId) {
        return transactionTemplate.execute(status ->
                townVersionRepository.readInCurrentTransaction(List.of(townId)))
                .versionOf(townId);
    }

    /** 번호는 호출부가 따로 올린다 — 여기서는 소속만 옮긴다. */
    private void moveTown(long placeId, long townId) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE places SET town_id = ? WHERE id = ?", townId, placeId);
            jdbcTemplate.update(
                    "UPDATE place_stats SET town_id = ? WHERE place_id = ?", townId, placeId);
        });
    }

    private void activate(long townId) {
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("UPDATE towns SET active = true WHERE id = ?", townId));
    }

    private static List<Long> ids(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeId).toList();
    }

    private static String nameOf(PlaceFilterGetResponse response, long placeId) {
        return response.places().stream()
                .filter(preview -> preview.placeId() == placeId)
                .map(PlacePreviewDto::placeName)
                .findFirst().orElseThrow();
    }

    private long createTown(String name, Long parentId, boolean active) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, ?, ?)",
                name, parentId, active);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private static int userSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /** 장소와 place_stats 행을 함께 심는다 — 정렬 다섯 축의 값을 직접 쥐여 준다. */
    private long createPlace(long town, String name, LocalDateTime createdAt,
            double popularScore, long bookmarkCount, long reviewCount, int ratingToInt) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '동네캐시IT', ?, true, ?)""", name, town, createdAt);
        long placeId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, tag_bitmask, name, popular_score,
                     bookmark_count, review_count, avg_rating)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?)""",
                placeId, town, createdAt, name, popularScore,
                bookmarkCount, reviewCount, ratingToInt / 100.0);
        return placeId;
    }

    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_list_town_versions");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            // 자식 동네가 부모를 FK로 가리킨다 — 자식을 먼저 지운다
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX
                    + "%' AND parent_id IS NOT NULL");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
