package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.SkeletonSource;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 골격 스냅샷의 <b>값</b>과 조회 경로의 <b>동치</b>를 실제 DB 위에서 문다.
 *
 * <p>이 캐시의 계약은 하나뿐이다 — <b>응답이 바뀌면 안 된다</b>. 그래서 단언도 셋으로 나뉜다.
 * <ol>
 *   <li>스냅샷이 만든 골격 = 엔티티 경로({@code findPlacesWithTagsByIds} + 게터)가 만드는 값.
 *       기대값을 손으로 적지 않고 <b>엔티티 경로를 실제로 돌려</b> 비교하는 것이 핵심이다 —
 *       손으로 적으면 두 경로가 함께 틀렸을 때 그린이 된다.</li>
 *   <li>{@code loadByIds}(프로젝션 모드의 출처)가 스냅샷과 같은 값을 내고, 스냅샷과 달리
 *       <b>비활성 장소도 담는다</b> — 미스 경로를 대신 서기 때문이다.</li>
 *   <li>세 모드({@code snapshot}/{@code projection}/{@code entity})의 응답 body와 커서 토큰이
 *       같다. A/B/C 측정의 <b>사전 게이트</b>이고, 판정보다 먼저 통과해야 하는 조건이다.</li>
 * </ol>
 *
 * <p><b>픽스처가 겨누는 갈림길.</b> 대표 태그와 썸네일은 규칙이 미묘해서 "대충 맞는" 구현이
 * 통과하기 쉽다. 그래서 장소마다 다른 함정을 심는다 — 비활성 MAIN 태그(쿼리에서 걸러내면
 * 다음 태그가 뽑힌다), MAIN이 아닌 태그만 가진 장소(조건을 WHERE로 올리면 장소가 통째로 사라진다),
 * display_order 역순 삽입(정렬을 빼면 삽입 순서가 그대로 나온다), 비활성 장소(스냅샷에서 빠지고
 * 미스 경로가 메워야 한다).
 */
@SpringBootTest
class PlaceSkeletonCacheIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void skeletonCacheProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "골격IT동네";
    private static final String USER_NICKNAME_PREFIX = "골격IT유저";
    private static final String TAG_NAME_PREFIX = "골격IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);

    @Autowired private PlaceSkeletonLoader loader;
    @Autowired private PlaceSkeletonSnapshot snapshot;
    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ImageUrlProvider imageUrlProvider;
    @Autowired private PlaceListProperties placeListProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private long townId;
    private long me;

    /** 활성 MAIN 태그 + 이미지 2장(삽입 순서와 display_order가 반대) */
    private long placeFull;
    /** 태그도 이미지도 없다 — 두 필드가 모두 null이어야 한다 */
    private long placeBare;
    /** MAIN 태그가 비활성 — 이름이 있는데도 null이어야 한다 */
    private long placeInactiveTag;
    /** OPTION1 태그만 있다 — MAIN이 없으므로 null이되, 장소 자체는 스냅샷에 있어야 한다 */
    private long placeOptionTagOnly;
    /** 비활성 장소 — 스냅샷에서 빠진다 */
    private long placeInactive;

    private String mainTagName;

    @BeforeEach
    void setUp() {
        townId = createTown(TOWN_NAME_PREFIX);
        me = createUser();

        placeFull = createPlace("골격A", true);
        // display_order를 역순으로 넣는다 — 정렬이 빠지면 삽입 순서(2번 이미지)가 썸네일이 된다
        insertImage(placeFull, "골격A_2번이미지", 2);
        insertImage(placeFull, "골격A_1번이미지", 1);
        long activeMainTag = createTag("MAIN", true);
        mainTagName = tagName(activeMainTag);
        linkTag(placeFull, activeMainTag);

        placeBare = createPlace("골격B", true);

        placeInactiveTag = createPlace("골격C", true);
        insertImage(placeInactiveTag, "골격C_이미지", 1);
        linkTag(placeInactiveTag, createTag("MAIN", false));

        placeOptionTagOnly = createPlace("골격D", true);
        insertImage(placeOptionTagOnly, "골격D_이미지", 1);
        linkTag(placeOptionTagOnly, createTag("OPTION1", true));

        placeInactive = createPlace("골격E", false);
        insertImage(placeInactive, "골격E_이미지", 1);

        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        loader.rebuild();
    }

    @AfterEach
    void restoreSkeletonSource() {
        placeListProperties.setSkeletonSource(SkeletonSource.SNAPSHOT);
    }

    /**
     * <b>스냅샷 = 엔티티 경로.</b> 네 필드 전부를 실제 엔티티 경로와 비교한다.
     * 기대값을 코드가 아니라 <em>같은 DB의 다른 경로</em>에서 얻는 것이 이 단언의 값어치다.
     */
    @Test
    void 스냅샷의_골격은_엔티티_경로가_만드는_값과_같다() {
        Map<Long, PlaceSkeleton> current = snapshot.current();

        for (long placeId : List.of(placeFull, placeBare, placeInactiveTag, placeOptionTagOnly)) {
            assertThat(current.get(placeId))
                    .as("placeId=%d", placeId)
                    .isEqualTo(expectedFromEntity(placeId));
        }
    }

    /**
     * 위 테스트는 두 경로가 <b>함께</b> 틀려도 그린이다. 그래서 규칙 자체도 값으로 못 박는다 —
     * 대표 태그가 null이 되는 이유가 셋(태그 없음 / MAIN 아님 / 비활성)인데 그중 어느 하나만
     * 지켜도 위 비교는 통과할 수 있다.
     */
    @Test
    void 대표_태그는_활성_MAIN_태그일_때만_이름을_싣는다() {
        Map<Long, PlaceSkeleton> current = snapshot.current();

        assertThat(current.get(placeFull).mainTagName()).isEqualTo(mainTagName);
        assertThat(current.get(placeBare).mainTagName()).isNull();
        assertThat(current.get(placeInactiveTag).mainTagName()).isNull();
        assertThat(current.get(placeOptionTagOnly).mainTagName()).isNull();
        // MAIN이 아닌 태그만 가진 장소가 스냅샷에서 사라지면 안 된다
        // (태그 조건을 파생 테이블이 아니라 바깥 WHERE로 올리면 여기가 깨진다)
        assertThat(current).containsKey(placeOptionTagOnly);
    }

    /** 썸네일은 {@code display_order}가 가장 앞선 이미지다 — 삽입 순서가 아니다 */
    @Test
    void 썸네일은_display_order가_가장_앞선_이미지의_URL이다() {
        Map<Long, PlaceSkeleton> current = snapshot.current();

        assertThat(current.get(placeFull).imageUrl())
                .isEqualTo(imageUrlProvider.getImageUrl("골격A_1번이미지"));
        assertThat(current.get(placeBare).imageUrl()).isNull();
    }

    /**
     * 비활성 장소는 스냅샷에 없다. 그 자체는 절반의 사실이고, 나머지 절반은
     * <b>목록에 남아 있는 창에서 미스 경로가 정확히 메운다</b>는 것이다 — 인기순은 카운트 배치가
     * 지울 때까지 비활성 장소를 계속 내보내므로({@code PlaceListDbQueryRepository} 계약)
     * 그때 골격이 비어 있으면 응답에 구멍이 난다.
     */
    @Test
    void 비활성_장소는_스냅샷에_없고_미스_경로가_메운다() {
        assertThat(snapshot.current()).doesNotContainKey(placeInactive);

        // 배치가 이미 활성이던 시절의 행을 만들어 두었으므로 인기순 결과에는 아직 남아 있다
        jdbcTemplate.update("UPDATE places SET active = false WHERE id = ?", placeFull);
        loader.rebuild();
        assertThat(snapshot.current()).doesNotContainKey(placeFull);

        // 이 클래스는 롤백하지 않아 회차마다 픽스처가 쌓인다 — 페이징을 걸면 대상이 페이지 밖으로
        // 밀려날 수 있으므로 size를 주지 않는다(= 전체 조회)
        PlacePreviewDto preview = previewOf(
                placeService.getPlaces(me, request(PlaceSortType.POPULAR, null, null)), placeFull);

        assertThat(preview.placeName()).isEqualTo("골격A");
        assertThat(preview.primaryTag()).isEqualTo(mainTagName);
        assertThat(preview.thumbnailImageUrl())
                .isEqualTo(imageUrlProvider.getImageUrl("골격A_1번이미지"));
    }

    /**
     * <b>미스로 읽은 값을 스냅샷에 넣지 않는다.</b> 넣기 시작하면 스냅샷이 "한 배치 회차의 사진"이
     * 아니라 "회차 + 그 뒤 요청들이 본 것"의 뒤섞임이 되어, 같은 장소가 언제의 값으로 보이는지
     * 말할 수 없게 된다. 조회를 여러 번 해도 스냅샷 크기가 그대로임을 값으로 못 박는다.
     */
    @Test
    void 미스로_읽은_장소는_스냅샷에_들어가지_않는다() {
        long newPlace = createPlace("골격신규", true);
        int sizeBefore = snapshot.current().size();

        // 최신순은 기준 테이블이 places라 배치 없이도 신규 장소가 맨 앞에 뜬다 = 확실한 미스
        PlaceFilterGetResponse response =
                placeService.getPlaces(me, request(PlaceSortType.LATEST, null, null));

        assertThat(previewOf(response, newPlace).placeName()).isEqualTo("골격신규");
        assertThat(snapshot.current()).doesNotContainKey(newPlace);
        assertThat(snapshot.current()).hasSize(sizeBefore);
    }

    /**
     * <b>{@code loadByIds}는 스냅샷과 같은 값을 낸다.</b> 프로젝션 모드가 값 동치를 <em>구현
     * 공유</em>로 얻는다는 주장의 근거이며, 두 쿼리 중 하나만 갈라져도(SELECT·파생 테이블·ORDER BY)
     * 여기서 걸린다.
     */
    @Test
    void loadByIds는_스냅샷_rebuild와_같은_골격을_낸다() {
        List<Long> ids = List.of(placeFull, placeBare, placeInactiveTag, placeOptionTagOnly);

        Map<Long, PlaceSkeleton> projected = transactionTemplate.execute(
                status -> loader.loadByIds(ids));

        assertThat(projected).containsOnlyKeys(ids.toArray(Long[]::new));
        for (long placeId : ids) {
            assertThat(projected.get(placeId))
                    .as("placeId=%d", placeId)
                    .isEqualTo(snapshot.current().get(placeId));
        }
    }

    /**
     * <b>{@code loadByIds}에는 활성 필터가 없다.</b> 이 메서드는 미스 경로
     * ({@code findPlacesWithTagsByIds} — 활성 여부를 묻지 않는다)를 통째로 대신 서므로,
     * 비활성화된 장소가 목록에 남아 있는 창(≤1h)에서도 같은 값을 내야 한다. 여기서 활성만
     * 거르면 프로젝션 모드의 응답에 구멍이 나고 모드 간 응답이 갈린다.
     */
    @Test
    void loadByIds는_비활성_장소도_담는다() {
        assertThat(snapshot.current()).doesNotContainKey(placeInactive);

        Map<Long, PlaceSkeleton> projected = transactionTemplate.execute(
                status -> loader.loadByIds(List.of(placeInactive)));

        assertThat(projected.get(placeInactive))
                .isEqualTo(expectedFromEntity(placeInactive));
    }

    /** 빈 목록에 IN ()을 내면 문법 오류다 — 쿼리를 아예 내지 않는 것이 계약이다 */
    @Test
    void loadByIds는_빈_목록에_쿼리를_내지_않는다() {
        assertThat(loader.loadByIds(List.of())).isEmpty();
    }

    /**
     * <b>정합성 게이트 — 세 모드의 응답 body와 커서 토큰이 같아야 한다.</b>
     * A/B/C 측정의 판정보다 <em>먼저</em> 통과해야 하는 조건이며, 여기가 빨간 상태로 낸 수치는
     * 서로 다른 응답의 비용을 비교한 것이라 아무 뜻이 없다.
     *
     * <p>두 정렬 × 두 페이지를 도는 이유: 커서 발급은 페이지 <b>마지막 행</b>에서 나오므로 첫
     * 페이지만 보면 커서 동치가 검증되지 않고, 정렬마다 골격을 붙이는 행의 출처가 다르다
     * (인기순은 place_stats, 최신순은 places).
     */
    @Test
    void 세_모드의_응답과_커서가_완전히_같다() {
        for (PlaceSortType sort : PlaceSortType.values()) {
            PlaceFilterGetResponse snapshotPage1 =
                    withSource(SkeletonSource.SNAPSHOT, () -> get(sort, null));
            assertThat(snapshotPage1.nextCursor()).as("%s 커서", sort).isNotNull();
            String cursor = snapshotPage1.nextCursor();

            for (SkeletonSource other : List.of(SkeletonSource.PROJECTION, SkeletonSource.ENTITY)) {
                assertThat(withSource(other, () -> get(sort, null)))
                        .as("%s 첫 페이지 - %s", sort, other)
                        .isEqualTo(snapshotPage1);
                assertThat(withSource(other, () -> get(sort, cursor)))
                        .as("%s 커서 페이지 - %s", sort, other)
                        .isEqualTo(withSource(SkeletonSource.SNAPSHOT, () -> get(sort, cursor)));
            }
        }
    }

    // === helpers ===

    private PlaceFilterGetResponse get(PlaceSortType sort, String cursor) {
        return placeService.getPlaces(me, request(sort, cursor, 2));
    }

    private <T> T withSource(SkeletonSource source, java.util.function.Supplier<T> action) {
        placeListProperties.setSkeletonSource(source);
        try {
            return action.get();
        } finally {
            placeListProperties.setSkeletonSource(SkeletonSource.SNAPSHOT);
        }
    }

    /**
     * 엔티티 경로가 만드는 골격. {@code getThumbnailFileKey}·{@code getMainTag}가 지연 로딩을
     * 건드리므로 트랜잭션 안에서 읽는다 ({@code open-in-view: false}).
     */
    private PlaceSkeleton expectedFromEntity(long placeId) {
        return transactionTemplate.execute(status -> {
            Place p = placeRepository.findPlacesWithTagsByIds(List.of(placeId)).get(0);
            return new PlaceSkeleton(
                    p.getId(),
                    p.getName(),
                    imageUrlProvider.getImageUrl(p.getThumbnailFileKey()),
                    TagViewUtils.getActiveNameOrNull(p.getMainTag().orElse(null)),
                    p.getTown().getId());
        });
    }

    private PlaceFilterGetRequest request(PlaceSortType sort, String cursor, Integer size) {
        return new PlaceFilterGetRequest(townId, false, null, null, null, sort, cursor, size);
    }

    private PlacePreviewDto previewOf(PlaceFilterGetResponse response, long placeId) {
        return response.places().stream()
                .filter(p -> p.placeId() == placeId)
                .findFirst().orElseThrow();
    }

    private long createTown(String name) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)", name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(String name, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '골격IT', ?, ?, ?)""", name, townId, active, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    private void insertImage(long placeId, String fileKey, int displayOrder) {
        jdbcTemplate.update("""
                INSERT INTO place_images (place_id, image_file_key, display_order)
                VALUES (?, ?, ?)""", placeId, fileKey, displayOrder);
    }

    private static int tagSeq = 0;
    private static int userSeq = 0;

    private long createTag(String type, boolean active) {
        String name = TAG_NAME_PREFIX + (++tagSeq);
        jdbcTemplate.update("""
                INSERT INTO tags (name, type, parent_id, active, tag_usage)
                VALUES (?, ?, NULL, ?, 'PLACE')""", name, type, active);
        return jdbcTemplate.queryForObject("SELECT id FROM tags WHERE name = ?", Long.class, name);
    }

    private String tagName(long tagId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM tags WHERE id = ?", String.class, tagId);
    }

    private void linkTag(long placeId, long tagId) {
        jdbcTemplate.update(
                "INSERT INTO place_tag (place_id, tag_id) VALUES (?, ?)", placeId, tagId);
    }

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /** {@code PlaceListFlowIT}과 같은 이유·같은 방식의 뒷정리 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_images WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            // place_tag는 places FK가 ON DELETE CASCADE라 places 삭제로 함께 사라진다(V7)
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
