package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.domain.place.util.TagMasks;
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
 * 표시값 홀더({@link PlaceViewHolder}·{@link TagViewHolder})가 담는 값이 엔티티 경로가 만드는
 * 값과 같은지를 실제 DB 위에서 문다.
 *
 * <p>이 캐시의 계약은 하나뿐이다 — <b>응답이 바뀌면 안 된다</b>. 순서·필터의 등가는
 * {@code PlaceListSnapshotEquivalenceIT}가 DB 정렬 경로와 나란히 돌려 지키고, 여기가 지키는 것은
 * "정해진 id에 붙는 네 값"(이름·썸네일 URL·대표 태그·동네)이다.
 *
 * <p><b>기대값을 손으로 적지 않는다.</b> 같은 DB의 <em>다른 경로</em>(엔티티 페치 + 게터)를 실제로
 * 돌려 비교하는 것이 이 파일의 방식이다 — 손으로 적으면 두 경로가 함께 틀렸을 때 그린이 된다.
 * 다만 등가만 보면 둘이 함께 규칙을 잃어도 그린이므로, 규칙 자체(대표 태그가 null이 되는 이유 셋 ·
 * 썸네일 선택 규칙)는 값으로도 못 박는다.
 *
 * <p><b>픽스처가 겨누는 갈림길.</b> 대표 태그와 썸네일은 규칙이 미묘해서 "대충 맞는" 구현이
 * 통과하기 쉽다. 그래서 장소마다 다른 함정을 심는다 — 비활성 MAIN 태그(쿼리에서 걸러내면 다음
 * 태그가 뽑힌다), MAIN이 아닌 태그만 가진 장소(조건을 파생 테이블이 아니라 바깥 WHERE로 올리면
 * 장소가 통째로 사라진다), display_order 역순 삽입(정렬을 빼면 삽입 순서가 그대로 나온다),
 * 빈 파일 키(값이 null인 항목을 "없음"으로 취급하면 다음 이미지가 대신 뽑힌다).
 *
 * <p><b>비활성 장소가 스냅샷에 남는 것은 의도다 (통합 스냅샷 전환).</b> 옛 골격 스냅샷은 활성만
 * 담았고 그 구멍을 요청 시점 미스 경로가 메웠는데, 지금은 행의 존재를 정하는 주체가
 * {@code place_stats} 하나뿐이라 미스라는 상태 자체가 없다 — 아래가 그 전환을 값으로 남긴다.
 */
@SpringBootTest
class PlaceListSnapshotLoaderIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void snapshotLoaderProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "엔트리IT동네";
    private static final String USER_NICKNAME_PREFIX = "엔트리IT유저";
    private static final String TAG_NAME_PREFIX = "엔트리IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);

    @Autowired private SnapshotLoader loader;
    @Autowired private SnapshotBox snapshotBox;
    @Autowired private PlaceViewHolder placeViewHolder;
    @Autowired private TagViewHolder tagViewHolder;
    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private ImageUrlProvider imageUrlProvider;
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
    /** 첫 이미지의 파일 키가 비어 있다 — 썸네일은 null이고 <b>둘째 이미지로 넘어가지 않는다</b> */
    private long placeBlankKey;
    /**
     * MAIN 태그가 둘인 비정상 데이터 — 뽑히는 것은 <b>{@code place_tag.id}가 작은 쪽</b>이다.
     * 먼저 붙인 쪽이 태그 id는 더 크고 활성도 아니라, 규칙이 태그 id 순으로 갈리거나 쿼리에
     * {@code t.active = 1}이 끼면 여기서 드러난다.
     */
    private long placeTwoMainTags;

    private String mainTagName;
    /** {@link #placeTwoMainTags}에 먼저 붙인 태그 = 뽑혀야 하는 쪽 */
    private long firstLinkedMainTagId;

    @BeforeEach
    void setUp() {
        townId = createTown(TOWN_NAME_PREFIX);
        me = createUser();

        placeFull = createPlace("엔트리A", true);
        // display_order를 역순으로 넣는다 — 정렬이 빠지면 삽입 순서(2번 이미지)가 썸네일이 된다
        insertImage(placeFull, "엔트리A_2번이미지", 2);
        insertImage(placeFull, "엔트리A_1번이미지", 1);
        long activeMainTag = createTag("MAIN", true);
        mainTagName = tagName(activeMainTag);
        linkTag(placeFull, activeMainTag);

        placeBare = createPlace("엔트리B", true);

        placeInactiveTag = createPlace("엔트리C", true);
        insertImage(placeInactiveTag, "엔트리C_이미지", 1);
        long inactiveMainTag = createTag("MAIN", false);
        linkTag(placeInactiveTag, inactiveMainTag);

        placeOptionTagOnly = createPlace("엔트리D", true);
        insertImage(placeOptionTagOnly, "엔트리D_이미지", 1);
        linkTag(placeOptionTagOnly, createTag("OPTION1", true));

        placeBlankKey = createPlace("엔트리E", true);
        insertImage(placeBlankKey, "", 1);
        insertImage(placeBlankKey, "엔트리E_2번이미지", 2);

        // 태그 id가 큰 쪽(= 나중에 만든 비활성 MAIN)을 먼저 붙인다. 새 태그를 만들지 않고 이미
        // 있는 둘을 재활용하는 것은 <b>비트마스크 상한(id 62) 예산</b> 때문이다 — 이 클래스는
        // 테스트마다 픽스처를 새로 심고 tag id는 MAX(id)+1로 올라가므로, 태그를 늘리면 회차가
        // 쌓여 상한에 닿는다.
        placeTwoMainTags = createPlace("엔트리F", true);
        linkTag(placeTwoMainTags, inactiveMainTag);
        linkTag(placeTwoMainTags, activeMainTag);
        firstLinkedMainTagId = inactiveMainTag;

        // 행을 짓는 것은 운영에서 어드민 쓰기 트랜잭션의 몫이고 배치는 값 칸만 정한다 —
        // 어드민 경로를 거치지 않는 이 픽스처는 원본 재구축 문장으로 그 자리를 채운다.
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        loader.rebuild();
    }

    /**
     * <b>홀더의 표시값 = 엔티티 경로.</b> 네 필드 전부를 실제 엔티티 경로와 비교한다.
     * 기대값을 코드가 아니라 <em>같은 DB의 다른 경로</em>에서 얻는 것이 이 단언의 값어치다.
     */
    @Test
    void 홀더의_표시값은_엔티티_경로가_만드는_값과_같다() {
        // placeTwoMainTags는 빠진다 — 엔티티 경로의 bag 순서는 fetch join에 ORDER BY가 없어
        // DB가 고른 인덱스 순(= tag id 순)이고, 캐시는 place_tag.id 순이다. MAIN이 둘인 비정상
        // 데이터에서만 갈리는 차이라 여기서는 겨누지 않는다(캐시 두 경로의 일치는 아래에서 문다).
        for (long placeId :
                List.of(placeFull, placeBare, placeInactiveTag, placeOptionTagOnly, placeBlankKey)) {
            assertThat(displayOf(placeId))
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
        assertThat(mainTagNameOf(placeFull)).isEqualTo(mainTagName);
        assertThat(mainTagNameOf(placeBare)).isNull();
        assertThat(mainTagNameOf(placeInactiveTag)).isNull();
        assertThat(mainTagNameOf(placeOptionTagOnly)).isNull();

        // 비활성이어도 <b>id는 담긴다</b> — 여기서 걸러내면 다음 MAIN 태그가 뽑혀 엔티티 경로와
        // 갈린다. 이름을 비우는 판정은 조회 시점 태그 맵의 active가 한다.
        assertThat(viewOf(placeInactiveTag).mainTagId()).isNotNull();
        assertThat(tagViewHolder.get(viewOf(placeInactiveTag).mainTagId()).active()).isFalse();
        // MAIN이 아닌 태그만 가진 장소가 스냅샷에서 사라지면 안 된다
        // (태그 조건을 파생 테이블이 아니라 바깥 WHERE로 올리면 여기가 깨진다)
        assertThat(entryOf(placeOptionTagOnly)).isNotNull();
    }

    /**
     * <b>MAIN 태그가 둘이면 {@code place_tag.id}가 작은 쪽이 뽑힌다.</b> 먼저 붙인 쪽은 태그 id가
     * 더 크고 활성도 아니므로, 정렬을 태그 id 순으로 바꾸거나 활성 조건을 끼워 넣으면 여기서
     * 드러난다.
     *
     * <p><b>겨누는 것은 캐시 두 경로(전량·{@code readView})의 일치</b>다. 엔티티 경로는 정본이
     * 아니다 — fetch join에 ORDER BY가 없어 bag 순서가 DB가 고른 인덱스 순이고, 지금 스키마에서는
     * 그것이 태그 id 순이라 여기와 갈린다. MAIN이 둘인 것 자체가 비정상 데이터라 어느 쪽이 옳다고
     * 정할 자리가 아니고, 대신 <b>캐시 안에서는 두 경로가 반드시 같은 답</b>을 내야 한다.
     *
     * <p>장소 하나가 행 둘을 내는 자리이기도 하다 — 로더가 직전 id 비교로 접지 않으면 같은 장소가
     * 정렬 배열에 두 번 선다.
     */
    @Test
    void MAIN_태그가_둘이면_place_tag_id가_작은_쪽이_대표다() {
        assertThat(viewOf(placeTwoMainTags).mainTagId()).isEqualTo(firstLinkedMainTagId);
        assertThat(loader.readView(placeTwoMainTags))
                .as("패치 경로도 같은 쪽을 뽑는다")
                .contains(viewOf(placeTwoMainTags));
        assertThat(entryCountOf(placeTwoMainTags)).as("행이 둘이어도 엔트리는 하나다").isEqualTo(1);
    }

    /** 썸네일은 {@code display_order}가 가장 앞선 이미지다 — 삽입 순서가 아니다 */
    @Test
    void 썸네일은_display_order가_가장_앞선_이미지의_URL이다() {
        assertThat(viewOf(placeFull).imageUrl())
                .isEqualTo(imageUrlProvider.getImageUrl("엔트리A_1번이미지"));
        assertThat(viewOf(placeBare).imageUrl()).isNull();
    }

    /**
     * <b>빈 파일 키의 답은 null이지 "다음 이미지"가 아니다.</b> 로더가 장소별 첫 행을
     * {@code putIfAbsent}·{@code computeIfAbsent}로 담으면 null을 "아직 없음"으로 취급해 둘째
     * 이미지를 대신 집어 든다 — 엔티티 경로는 그 경우 null 그대로라 두 경로가 갈린다.
     */
    @Test
    void 첫_이미지의_키가_비어_있으면_썸네일은_null이고_다음_이미지로_넘어가지_않는다() {
        assertThat(viewOf(placeBlankKey).imageUrl()).isNull();
        assertThat(viewOf(placeBlankKey).imageUrl())
                .isNotEqualTo(imageUrlProvider.getImageUrl("엔트리E_2번이미지"));
    }

    /**
     * <b>{@code readView} 한 건이 전량 재빌드와 같은 규칙을 낸다.</b> 어드민 패치가 쓰는 경로라
     * 규칙이 갈리면 같은 장소가 "패치된 뒤"와 "다음 회차 뒤"에 다르게 보인다 — 함정을 심어 둔
     * 픽스처 전부(비활성 MAIN · MAIN 없음 · display_order 역순 · 빈 파일 키)로 확인한다.
     */
    @Test
    void readView는_전량_재빌드와_같은_표시값을_낸다() {
        for (long placeId :
                List.of(placeFull, placeBare, placeInactiveTag, placeOptionTagOnly, placeBlankKey,
                        placeTwoMainTags)) {
            assertThat(loader.readView(placeId))
                    .as("placeId=%d", placeId)
                    .contains(viewOf(placeId));
        }
    }

    /** 없는 장소는 빈 값이다 — 호출자가 맵을 건드리지 않는 근거다 */
    @Test
    void readView는_없는_장소에_빈_값을_낸다() {
        long missing = jdbcTemplate.queryForObject("SELECT MAX(id) + 1 FROM places", Long.class);

        assertThat(loader.readView(missing)).isEmpty();
    }

    /**
     * <b>비활성 장소도 행이 있는 한 스냅샷에 남고, 표시값도 온전하다.</b>
     *
     * <p>옛 골격 스냅샷은 활성만 담아 이 자리에 "미스 경로가 메운다"는 절반이 필요했다. 지금은
     * 행의 존재를 정하는 주체가 {@code place_stats} 하나뿐이고 배치는 행을 지우지 않으므로,
     * 내려간 장소가 목록에 남아 있는 창에서도 스냅샷이 그 값을 그대로 들고 있다 — 요청 시점에
     * 메울 것이 없다는 뜻이다. 로더 쿼리에 {@code p.active} 조건이 붙으면 여기가 빨개진다.
     */
    @Test
    void 비활성_장소도_행이_있으면_스냅샷에_남고_목록_표시값이_온전하다() {
        jdbcTemplate.update("UPDATE places SET active = false WHERE id = ?", placeFull);
        loader.rebuild();

        assertThat(entryOf(placeFull)).isNotNull();

        // 이 클래스는 롤백하지 않아 회차마다 픽스처가 쌓인다 — 페이징을 걸면 대상이 페이지 밖으로
        // 밀려날 수 있으므로 size를 주지 않는다(= 전체 조회)
        PlacePreviewDto preview = previewOf(
                placeService.getPlaces(me, request(PlaceSortType.LATEST, null)), placeFull);

        assertThat(preview.placeName()).isEqualTo("엔트리A");
        assertThat(preview.primaryTag()).isEqualTo(mainTagName);
        assertThat(preview.thumbnailImageUrl())
                .isEqualTo(imageUrlProvider.getImageUrl("엔트리A_1번이미지"));
    }

    /**
     * <b>스냅샷은 한 회차의 것이고, 다시 짓기 전에는 새 장소를 보지 않는다.</b> 그 지연이 버그가
     * 아니라 이 캐시의 정의라는 것을 값으로 남긴다.
     *
     * <p>여기서 지연이 보이는 것은 이 픽스처가 <b>어드민 경로를 지나치기</b> 때문이다 — 어드민
     * 쓰기는 커밋 뒤 스스로 스냅샷을 다시 지으므로({@code SnapshotRefresher}) 그 경로의
     * 변경은 이 창을 만들지 않고, 배치가 채우는 카운트·점수만 다음 타이머 회차를 기다린다
     * ({@code SnapshotScheduler}).
     */
    @Test
    void 다시_짓기_전에는_새_장소가_스냅샷에_없다() {
        long added = createPlace("엔트리신규", true);
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT.plusHours(1));

        assertThat(entryOf(added)).as("아직 이 회차의 스냅샷에는 없다").isNull();

        loader.rebuild();

        assertThat(entryOf(added)).as("다음 회차부터 보인다").isNotNull();
    }

    // === helpers ===

    /** 엔트리가 담는 표시 필드 넷 — 두 경로의 비교 단위다 */
    private record Display(String name, String imageUrl, String mainTagName, long townId) {}

    /**
     * 최신 회차의 스냅샷에서 이 장소의 엔트리를 찾는다. 정렬 배열에 id 조회구가 없는 것은 의도이므로
     * (조회 경로가 쓰지 않는다) 정렬 없는 축으로 전량을 훑어 고른다.
     */
    private PlaceEntry entryOf(long placeId) {
        return snapshotBox.current().sortedPlaces()
                .page(PlaceSortType.LATEST, List.of(townId), TagMasks.of(null, null, null),
                        null, Integer.MAX_VALUE - 1)
                .stream()
                .filter(entry -> entry.placeId() == placeId)
                .findFirst().orElse(null);
    }

    /** 같은 장소의 엔트리가 몇 개인지 — MAIN 태그가 둘인 장소가 두 번 서면 안 된다 */
    private long entryCountOf(long placeId) {
        return snapshotBox.current().sortedPlaces()
                .page(PlaceSortType.LATEST, List.of(townId), TagMasks.of(null, null, null),
                        null, Integer.MAX_VALUE - 1)
                .stream()
                .filter(entry -> entry.placeId() == placeId)
                .count();
    }

    /** 표시값은 스냅샷이 아니라 홀더에 있다 — 조회 경로가 조립하는 자리와 같은 곳에서 읽는다 */
    private PlaceView viewOf(long placeId) {
        return placeViewHolder.get(placeId);
    }

    /** {@code PlaceService}가 응답을 조립할 때 하는 판정과 같아야 한다 */
    private String mainTagNameOf(long placeId) {
        PlaceView view = viewOf(placeId);
        if (view == null || view.mainTagId() == null) {
            return null;
        }
        TagView tag = tagViewHolder.get(view.mainTagId());
        return tag != null && tag.active() ? tag.name() : null;
    }

    private Display displayOf(long placeId) {
        PlaceView view = viewOf(placeId);
        return new Display(view.name(), view.imageUrl(), mainTagNameOf(placeId),
                entryOf(placeId).townId());
    }

    /**
     * 엔티티 경로가 만드는 표시값. {@code getThumbnailFileKey}·{@code getMainTag}가 지연 로딩을
     * 건드리므로 트랜잭션 안에서 읽는다 ({@code open-in-view: false}).
     */
    private Display expectedFromEntity(long placeId) {
        return transactionTemplate.execute(status -> {
            Place p = placeRepository.findPlacesWithTagsByIds(List.of(placeId)).get(0);
            return new Display(
                    p.getName(),
                    imageUrlProvider.getImageUrl(p.getThumbnailFileKey()),
                    TagViewUtils.getActiveNameOrNull(p.getMainTag().orElse(null)),
                    p.getTown().getId());
        });
    }

    private PlaceFilterGetRequest request(PlaceSortType sort, Integer size) {
        return new PlaceFilterGetRequest(
                townId, false, null, null, null, sort, null, size, null, null);
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
                VALUES (?, '엔트리IT', ?, ?, ?)""", name, townId, active, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    private void insertImage(long placeId, String fileKey, int displayOrder) {
        jdbcTemplate.update("""
                INSERT INTO place_images (place_id, image_file_key, display_order)
                VALUES (?, ?, ?)""", placeId, fileKey, displayOrder);
    }

    private static int tagSeq = 0;
    private static int userSeq = 0;

    /**
     * <b>태그 id를 auto-increment에 맡기지 않는다.</b> V34부터 태그 id가 곧
     * {@code place_stats.tag_bitmask}의 비트 자리라 62를 넘으면 안 되는데
     * ({@code TagBitmask}), auto-increment 카운터는 롤백해도 되돌아가지 않아 같은 싱글턴 컨테이너를
     * 나눠 쓰는 IT가 늘수록 상한에 다가간다. {@code MAX(id) + 1}은 뒷정리를 따라 되돌아간다.
     */
    private long createTag(String type, boolean active) {
        String name = TAG_NAME_PREFIX + (++tagSeq);
        Long tagId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, ?, NULL, ?, 'PLACE')""", tagId, name, type, active);
        return tagId;
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
