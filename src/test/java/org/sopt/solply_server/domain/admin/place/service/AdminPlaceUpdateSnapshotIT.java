package org.sopt.solply_server.domain.admin.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.place.cache.SnapshotBox;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.domain.place.cache.SnapshotRebuilder;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 어드민 장소 쓰기가 <b>커밋을 건너 목록에 닿는 경로 전체</b> — 트랜잭션 커밋 → 번호/즉시 패치 →
 * 리빌드 → 조회 응답 — 를 실제 DB로 밟는다. 단위 테스트는 서비스가 어느 문을 두드리는지까지만
 * 보므로, 그것이 <b>정말 화면을 바꾸는지</b>·<b>정말 커서를 지키는지</b>는 여기서만 드러난다.
 *
 * <p><b>어드민 수정이 목록에 닿는 길은 둘이고, 도착 시점이 다르다.</b>
 * <ul>
 *   <li><b>표시값</b>(이름·썸네일·대표 태그) — 커밋 직후 이 인스턴스의 홀더에 곧바로 얹힌다.
 *       리빌드를 기다리지 않는다.</li>
 *   <li><b>순서와 소속</b>(동네·태그 비트·좌표·행의 존재) — 다음 리빌드가 원본을 통째로 다시
 *       읽어 반영한다. 어드민 요청 스레드는 그것을 기다리지 않는다.</li>
 * </ul>
 * 아래 시나리오들이 그 둘을 갈라 못 박는다. 리빌드가 필요한 쪽에서는 {@link #rebuild()}를 부르고,
 * 운영에서 그 자리를 채우는 것은 1초 폴이다.
 *
 * <p><b>커서 축.</b> 어드민 수정은 <b>기본적으로 진행 중인 스크롤을 끊지 않는다</b> — 동네를
 * 옮기든 장소를 지우든 마찬가지다. 끊는 것은 요청이 {@code restartPlaceList}로 명시했을 때뿐이다.
 */
@SpringBootTest
class AdminPlaceUpdateSnapshotIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void adminUpdateProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        // 매시 회차가 둘로 갈렸다(2026-09-12) — 새 키를 빠뜨리면 :15에 델타 소비가 깨어난다
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "어드민수정IT동네";
    private static final String USER_NICKNAME_PREFIX = "어드민수정IT유저";
    private static final String TAG_NAME_PREFIX = "어드민수정IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);
    private static final double LATITUDE = 37.5;
    private static final double LONGITUDE = 127.0;
    private static final String NEIGHBOR_NAME = "손대지않을장소";
    /** 이웃은 기준점에서 더 멀다 — 거리순의 초기 순서를 정해 두어야 뒤집힘이 증거가 된다 */
    private static final double NEIGHBOR_LATITUDE = 37.6;
    /** 이웃보다도 멀어지는 자리 — 좌표 수정이 거리순 자리를 실제로 옮기는지 재는 값 */
    private static final double FAR_LATITUDE = 37.9;

    @Autowired private AdminPlaceService adminPlaceService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SnapshotMetadataRepository snapshotMetadataRepository;
    @Autowired private SnapshotInstaller snapshotInstaller;
    @Autowired private SnapshotBox snapshotBox;
    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long townId;
    private long otherTownId;
    private long me;
    private long placeId;
    /** 같은 동네의 <b>손대지 않을</b> 장소 — 수정의 영향 범위를 재는 자다 */
    private long neighborPlaceId;
    private long mainTagId;
    private long optionTagId;

    @BeforeEach
    void setUp() {
        townId = createTown();
        otherTownId = createTown();
        me = createUser();

        mainTagId = createTag("MAIN", null);
        // 옵션 태그의 부모가 메인 태그여야 어드민 수정이 태그 검증을 통과한다
        optionTagId = createTag("OPTION1", mainTagId);

        placeId = createPlace("수정전이름", LATITUDE);
        linkTag(placeId, mainTagId);
        linkTag(placeId, optionTagId);

        neighborPlaceId = createPlace(NEIGHBOR_NAME, NEIGHBOR_LATITUDE);
        linkTag(neighborPlaceId, mainTagId);
        linkTag(neighborPlaceId, optionTagId);

        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        new SnapshotRebuilder(snapshotInstaller, snapshotMetadataRepository, transactionManager)
                .rebuildAndInstall();
    }

    /**
     * <b>이름은 리빌드를 기다리지 않는다.</b> 표시값은 스냅샷 밖 홀더에 살고, 어드민 커밋 직후
     * 그 홀더에 곧바로 얹힌다. 그리고 표시값은 순서를 건드리지 않으므로 커서도 그대로다.
     */
    @Test
    void 이름만_고친_수정은_리빌드_없이_새_이름을_올리고_커서를_지킨다() {
        long cursorVersionBefore = cursorVersion();

        adminPlaceService.updatePlace(placeId, request("수정후이름", townId, LATITUDE));

        assertThat(previewOf(previews(townId), placeId).placeName()).isEqualTo("수정후이름");
        assertThat(cursorVersion())
                .as("표시값만 바뀌었으므로 진행 중인 스크롤을 끊지 않는다")
                .isEqualTo(cursorVersionBefore);
    }

    /**
     * <b>이름은 place_stats의 칸이고, 즉시 패치가 읽는 원천이 그 칸이다 (V40).</b> 수정이 upsert를
     * 건너뛰면 행에 옛 이름이 남고 — 패치도 리빌드도 그것을 읽으므로 — 화면이 조용히 낡는다.
     * 위 테스트가 화면 값만 보므로, 그 값이 어디서 왔는지를 여기서 못 박는다.
     */
    @Test
    void 이름만_고친_수정도_place_stats의_이름을_다시_짓는다() {
        adminPlaceService.updatePlace(placeId, request("수정후이름", townId, LATITUDE));

        assertThat(statsNameOf(placeId)).isEqualTo("수정후이름");
        assertThat(previewOf(previews(townId), placeId).placeName())
                .as("패치가 그 칸을 읽어 화면으로 옮긴다").isEqualTo("수정후이름");
    }

    /**
     * <b>어드민 요청 스레드는 스냅샷을 짓지 않는다.</b> 어드민이 남기는 것은 번호 하나이고, 짓는
     * 것은 각 인스턴스의 리빌드다. 이 분리가 없으면 어드민 응답 시간이 장소 수에 비례하고, 인스턴스가
     * 여럿일 때 어드민이 만진 그 한 대만 최신이 된다.
     */
    @Test
    void 어드민_수정은_요청_스레드에서_스냅샷을_짓지_않는다() {
        SnapshotMetadata installedBefore = snapshotInstaller.installed();

        adminPlaceService.updatePlace(placeId, request("수정후이름", otherTownId, LATITUDE));

        assertThat(snapshotInstaller.installed())
                .as("설치된 시점은 그대로다 — 지은 사람이 없다").isEqualTo(installedBefore);
        assertThat(snapshotMetadataRepository.read().isNewerThan(installedBefore))
                .as("대신 번호는 올라 있다 — 폴이 그것을 본다").isTrue();
    }

    /**
     * <b>동네 이동은 리빌드가 반영하고, 그래도 커서는 살아 있다.</b> 소속이 바뀌는 큰 변경이지만
     * 자동으로 스크롤을 끊지 않는다는 것이 계약이다 ({@code SnapshotCursorPolicy}).
     */
    @Test
    void 동네를_옮긴_수정은_리빌드_뒤_새_동네_목록으로_옮겨진다() {
        long cursorVersionBefore = cursorVersion();

        adminPlaceService.updatePlace(placeId, request("수정전이름", otherTownId, LATITUDE));
        rebuild();

        assertThat(idsOf(previews(townId)))
                .as("옮기기 전 동네에서 빠졌다").doesNotContain(placeId);
        assertThat(previewOf(previews(otherTownId), placeId).placeName()).isEqualTo("수정전이름");
        assertThat(cursorVersion())
                .as("기본은 스크롤 유지다").isEqualTo(cursorVersionBefore);
    }

    /**
     * <b>어드민이 명시로 고르면 그때 목록이 새로 시작된다.</b> 대량 정리처럼 순서가 크게 갈리는
     * 작업에서 쓰는 자리이고, 이것이 유일하게 커서를 끊는 어드민 경로다.
     */
    @Test
    void 요청이_명시하면_커서_회차가_오르고_옛_커서가_만료된다() {
        long cursorVersionBefore = cursorVersion();

        adminPlaceService.updatePlace(
                placeId, restarting(request("수정전이름", otherTownId, LATITUDE)));
        rebuild();

        assertThat(cursorVersion()).isGreaterThan(cursorVersionBefore);
    }

    /**
     * <b>새 장소는 리빌드가 목록에 세운다.</b> 정렬 배열에 자리가 하나 느는 변경이라 표시값 패치로는
     * 닿지 않는다 — 배열은 어느 한 시점의 완결된 것이어야 하고, 부분 삽입은 그 계약을 깬다.
     */
    @Test
    void 새로_만든_장소는_리빌드_뒤_목록에_선다() {
        long created = adminPlaceService
                .createPlace(me, request("새로만든장소", townId, LATITUDE)).placeId();

        rebuild();

        assertThat(previewOf(previews(townId), created).placeName()).isEqualTo("새로만든장소");
    }

    /**
     * <b>지운 장소는 리빌드를 기다리지 않고 목록에서 빠진다.</b> 즉시 패치가 그 id를 다시 읽어
     * <b>행이 없는 것</b>을 보고 표시값을 지우고, 조회 경로가 표시값 없는 행을 건너뛰기 때문이다.
     * 배열 자체에서 빠지는 것은 그다음 리빌드의 몫이다.
     */
    @Test
    void 지운_장소는_리빌드_전에_이미_목록에서_빠진다() {
        adminPlaceService.deletePlace(placeId, SnapshotCursorPolicy.PRESERVE);

        assertThat(idsOf(previews(townId))).doesNotContain(placeId);
        assertThat(idsOf(previews(townId)))
                .as("같은 동네의 다른 장소는 그대로다").contains(neighborPlaceId);

        rebuild();
        assertThat(idsOf(previews(townId))).doesNotContain(placeId);
    }

    /**
     * <b>뗀 태그로는 더 이상 검색되지 않는다.</b> 태그 조건은 {@code place_stats.tag_bitmask}로
     * 걸리므로, 이 값이 낡으면 순위가 아니라 <b>결과 집합</b>이 틀린다 — 화면에는 아무 오류도
     * 나지 않는다. 비트는 정렬 배열이 싣는 값이라 리빌드가 반영한다.
     */
    @Test
    void 태그를_뗀_수정은_리빌드_뒤_그_태그_필터_결과에서_빠진다() {
        assertThat(idsOf(previewsWithOptionTag(townId)))
                .as("떼기 전에는 그 태그로 검색된다").contains(placeId);

        adminPlaceService.updatePlace(placeId, requestWithoutOptionTag());
        rebuild();

        assertThat(idsOf(previewsWithOptionTag(townId)))
                .as("뗀 태그로는 더 이상 검색되지 않는다").doesNotContain(placeId);
        assertThat(idsOf(previews(townId)))
                .as("태그를 뗐을 뿐 목록에서 사라지는 것은 아니다").contains(placeId);
    }

    /**
     * <b>좌표 수정은 거리순 자리를 옮긴다.</b> 거리순은 사전 정렬이 없어 요청마다 좌표를 다시
     * 재므로, 스냅샷의 좌표가 낡으면 <b>거리 자체가 틀린 채</b> 정렬된다.
     */
    @Test
    void 좌표를_옮긴_수정은_리빌드_뒤_거리순_자리를_바꾼다() {
        assertThat(idsOf(distancePreviews(townId)))
                .as("옮기기 전에는 기준점에 더 가깝다")
                .containsExactly(placeId, neighborPlaceId);

        adminPlaceService.updatePlace(placeId, request("수정전이름", townId, FAR_LATITUDE));
        rebuild();

        assertThat(idsOf(distancePreviews(townId)))
                .as("이웃보다 멀어졌으므로 뒤로 간다")
                .containsExactly(neighborPlaceId, placeId);
    }

    // === helpers ===

    /**
     * 리빌드 한 번 — 운영에서 이 자리를 채우는 것은 1초 폴이다.
     *
     * <p><b>번호를 올리지 않는다.</b> 어드민 경로가 번호를 올려 두지 않았으면 설치자의 단조 가드에
     * 걸려 아무 일도 일어나지 않는다 — 그래서 이 호출을 끼운 단언에도 이빨이 남는다.
     */
    private void rebuild() {
        snapshotInstaller.rebuildAndInstall(observed -> {
        });
    }

    private long cursorVersion() {
        return snapshotBox.current().metadata().cursorVersion();
    }

    /** 픽스처와 모든 값이 같은 수정 요청 — 인자로 받은 칸 하나만 다르다 */
    private AdminPlaceUpsertRequest request(String name, long town, double latitude) {
        return new AdminPlaceUpsertRequest(
                name, "소개", "주소", latitude, LONGITUDE, town,
                mainTagId, List.of(optionTagId), List.of(), List.of(),
                "02-000-0000", "매일 09:00-18:00", Map.of(), List.of(), null);
    }

    /** 같은 요청에 "목록을 새로 시작한다"만 켠 것 */
    private static AdminPlaceUpsertRequest restarting(AdminPlaceUpsertRequest req) {
        return new AdminPlaceUpsertRequest(
                req.name(), req.introduction(), req.address(), req.latitude(), req.longitude(),
                req.townId(), req.mainTagId(), req.option1TagIds(), req.option2TagIds(),
                req.imageFileKeys(), req.contactNumber(), req.openingHours(),
                req.snsLinks(), req.placeCheckpoints(), true);
    }

    /** 픽스처와 모든 값이 같되 옵션 태그만 뗀 수정 요청 */
    private AdminPlaceUpsertRequest requestWithoutOptionTag() {
        return new AdminPlaceUpsertRequest(
                "수정전이름", "소개", "주소", LATITUDE, LONGITUDE, townId,
                mainTagId, List.of(), List.of(), List.of(),
                "02-000-0000", "매일 09:00-18:00", Map.of(), List.of(), null);
    }

    private List<PlacePreviewDto> previews(long town) {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                town, false, null, null, null, PlaceSortType.LATEST, null, null, null, null))
                .places();
    }

    /** 메인 + 옵션 태그를 함께 건 목록 — 그룹 간 AND라 옵션을 떼면 여기서 빠진다 */
    private List<PlacePreviewDto> previewsWithOptionTag(long town) {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                town, false, mainTagId, List.of(optionTagId), null,
                PlaceSortType.LATEST, null, null, null, null))
                .places();
    }

    /** 기준점은 픽스처의 원래 좌표다 — 그 자리에 선 장소가 앞이어야 한다 */
    private List<PlacePreviewDto> distancePreviews(long town) {
        return placeService.getPlaces(me, new PlaceFilterGetRequest(
                town, false, null, null, null, PlaceSortType.DISTANCE, null, null,
                LATITUDE, LONGITUDE))
                .places();
    }

    private static List<Long> idsOf(List<PlacePreviewDto> previews) {
        return previews.stream().map(PlacePreviewDto::placeId).toList();
    }

    private String statsNameOf(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM place_stats WHERE place_id = ?", String.class, placeId);
    }

    private static PlacePreviewDto previewOf(List<PlacePreviewDto> previews, long placeId) {
        return previews.stream()
                .filter(preview -> preview.placeId() == placeId)
                .findFirst().orElseThrow();
    }

    private static int tagSeq = 0;
    private static int userSeq = 0;

    private long createTown() {
        jdbcTemplate.update("INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(String name, double latitude) {
        jdbcTemplate.update("""
                INSERT INTO places (
                    name, introduction, address, latitude, longitude,
                    town_id, created_by, active, created_at)
                VALUES (?, '소개', '주소', ?, ?, ?, ?, true, ?)""",
                name, latitude, LONGITUDE, townId, me, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private long createTag(String type, Long parentId) {
        String name = TAG_NAME_PREFIX + (++tagSeq);
        Long tagId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, ?, ?, true, 'PLACE')""", tagId, name, type, parentId);
        return tagId;
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

    /**
     * {@code PlaceListSnapshotLoaderIT}과 같은 이유의 뒷정리.
     * <p>
     * place_stats만 그쪽과 다르다 — 이 클래스의 픽스처 장소로 범위를 좁힌다.
     * 같은 컨테이너를 쓰는 다른 IT가 커밋해 둔 칸까지 지우지 않기 위해서다.
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM place_tag WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            // 자식(옵션) 태그를 먼저 지운다 — parent_id FK가 역순 삭제를 막는다
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX
                    + "%' AND parent_id IS NOT NULL");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
