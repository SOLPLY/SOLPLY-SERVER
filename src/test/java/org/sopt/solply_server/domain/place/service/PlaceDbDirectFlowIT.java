package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code solply.place-list.popular-read-mode=db}(캐시를 거치지 않는 DB 직행 경로)로 같은 사슬을 걷는다 —
 * 북마크·리뷰 INSERT → 배치 → 조회 → 정렬 → 커서 왕복 → 이벤트 증분.
 *
 * <p><b>이 파일의 존재 이유는 커버리지가 아니라 정합성이다.</b> 두 모드의 랭킹 소스가
 * {@code place_stats.popular_score} 하나로 통일돼 있어 <b>응답과 커서가 모드 간 호환되고 동일해야</b>
 * 하고, 그 성질이 A/B 벤치의 응답 diff 검증을 성립시킨다. 그래서 {@link PlacePopularFlowIT}와
 * <b>같은 픽스처·같은 기대값</b>을 쓴다 — 두 IT가 같은 캐논을 통과하는 것 자체가 모드 간 정합의 증명이다.
 * 기대값을 공유 상수로 빼지 않고 양쪽 파일에 리터럴로 적어 두는 것도 같은 이유다: 상수를 공유하면
 * "두 모드가 똑같이 틀린" 회귀를 아무도 못 잡는다.
 *
 * <p>다만 <b>정렬 축은 원본보다 하나 넓다</b>. db 모드는 POPULAR뿐 아니라 LATEST까지 DB로 서빙하므로
 * (캐시 경로에서 LATEST의 정렬 키는 스냅샷 안에 있어 {@code PlaceListPaginatorTest}가 단위로 맡는다)
 * 여기서는 LATEST의 생성일 내림차순·id 타이브레이크·커서 왕복을 사슬 수준에서 한 번 더 문다.
 *
 * <p><b>계약: 단언은 PlaceService 응답 DTO 수준으로만 한다.</b> 내부 표현(네이티브 SQL의 컬럼 순서,
 * 레포지토리 record 모양)이 바뀌는 리팩터링에서 이 파일은 수정 없이 그린이어야 한다.
 */
@SpringBootTest
class PlaceDbDirectFlowIT extends MySqlContainerSupport {

    /**
     * 메서드 이름은 베이스의 {@code datasource}·원본의 {@code flowItProps}와 반드시 달라야 한다.
     * {@code @DynamicPropertySource}는 static이라 같은 이름이면 상위/동명 메서드를 <em>숨겨</em>
     * 설정이 통째로 사라진다. (이름이 다르면 {@code MergedContextConfiguration}도 갈려
     * {@code popular-read-mode=db}인 컨텍스트가 캐시 모드 IT와 섞이지 않는다.)
     *
     * <p><b>배치 스케줄을 꺼야 하는 이유.</b> {@code @SpringBootTest}는 실제 앱을 띄우므로
     * {@code PlaceStatsFacade.recalculatePlaceStats}의 {@code @Scheduled(cron = "0 0 2 * * *")}가
     * 그대로 등록된다. 하필 {@link #CALCULATED_AT}이 02:00이라, 스위트 실행이 실제 벽시계 02:00을
     * 지나면 스케줄러가 {@code recalculateAll(now())}를 돌려 픽스처가 의존하는 place_stats를
     * 통째로 다른 세대로 덮어쓴다. {@code "-"}는 스프링이 "등록하지 않음"으로 해석하는 센티널이다
     * ({@code Scheduled.CRON_DISABLED}).
     */
    @DynamicPropertySource
    static void dbDirectProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.cron", () -> "-");
        registry.add("solply.place-list.popular-read-mode", () -> "db");
    }

    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 증분 이벤트의 발행 주체. 리포지토리를 직접 부르면 "발행 가드"와 배선이 검증에서 빠진다. */
    @Autowired private BookmarkService bookmarkService;

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** 픽스처의 장소 생성 시각. 세 장소가 같은 초라 POPULAR 쪽은 시각 축을 쓰지 않는다 */
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);

    /**
     * 뒷정리가 이 테스트의 픽스처를 역추적하는 유일한 기준점.
     * <b>원본 {@link PlacePopularFlowIT}의 상수("사슬IT동네"/"사슬IT유저")와 반드시 달라야 한다</b> —
     * 같으면 먼저 끝난 쪽의 {@code @AfterAll}이 아직 도는 쪽의 픽스처를 지운다.
     * ({@code _}·{@code %}가 들어가면 아래 LIKE에서 와일드카드가 되므로 넣지 않는다.)
     */
    private static final String TOWN_NAME_PREFIX = "db직행IT동네";

    /** 인기순 픽스처가 사는 동네 */
    private static final String POPULAR_TOWN_NAME = TOWN_NAME_PREFIX + "인기";

    /** 최신순 픽스처가 사는 동네 — 인기순 동네와 나누는 이유는 최신순 테스트의 주석 참조 */
    private static final String LATEST_TOWN_NAME = TOWN_NAME_PREFIX + "최신";

    /** users.nickname UNIQUE — 원본 사슬 IT('사슬IT유저')·배치 IT('배치테스트유저')와 겹치지 않는 접두사 */
    private static final String USER_NICKNAME_PREFIX = "db직행IT유저";

    /** 태그 필터 배선 검증용 태그의 이름 접두사 — 뒷정리가 이것으로 되찾는다 */
    private static final String TAG_NAME_PREFIX = "db직행IT태그";

    // 점수가 전부 ≈인 것은 감쇠항 POW(0.5, 경과/90)이 "기준시각 1분 전"에도 미세하게 걸리기 때문이다
    // (실측: C=5.999968, A=3.999979). 90일 전 북마크만 정확히 절반이라 B는 딱 떨어진다.
    private long townId;
    private long placeA;   // 기준시각 1분 전 남의 북마크 4건 → 점수 ≈ 4.0
    private long placeB;   // 90일 전 북마크 5건(남 4 + 나 1) → 점수 = 2.5 (정확히 절반)
    private long placeC;   // 5점 리뷰 1건 → 점수 ≈ 6.0
    private long me;       // 조회 주체 — 배치 "전"에 placeB를, 배치 "후"에 placeC를 북마크

    @BeforeEach
    void setUp() {
        townId = createTown(POPULAR_TOWN_NAME);
        placeA = createPlace(townId, "db직행A", PLACE_CREATED_AT);
        placeB = createPlace(townId, "db직행B", PLACE_CREATED_AT);
        placeC = createPlace(townId, "db직행C", PLACE_CREATED_AT);
        me = createUser();

        for (int i = 0; i < 4; i++) {
            long user = createUser();
            insertBookmark(user, placeA, CALCULATED_AT.minusMinutes(1));
            insertBookmark(user, placeB, CALCULATED_AT.minusDays(90));
        }
        insertReview(createUser(), placeC, 5, CALCULATED_AT.minusMinutes(1));

        // 내 북마크지만 배치 "이전" — 배치가 이미 센 쪽이다 (placeB 카운트 5의 다섯 번째)
        insertBookmark(me, placeB, CALCULATED_AT.minusDays(90));

        batchProcessor.recalculateAll(CALCULATED_AT);

        // 내 북마크지만 배치 "이후"이고, 이벤트가 아니라 직접 INSERT라 증분도 없다 —
        // place_stats는 0인데 isBookmarked는 true인 상태를 만든다.
        // 표시 보정이 되살아나면 이 조합에서만 카운트가 1로 부풀어 즉시 잡힌다.
        insertBookmark(me, placeC, CALCULATED_AT.plusMinutes(30));

        // 원본 setUp 끝의 townPlacesCache.invalidate(townId)는 여기에 옮기지 않았다.
        // db 모드의 목록 경로(PlaceService.listFromDb)는 TownPlacesCache를 아예 조회하지 않으므로
        // (캐시를 타는 것은 북마크 검색뿐이고 이 파일은 그 경로를 걷지 않는다) 무효화는 무조건 no-op이고,
        // 남겨 두면 "db 모드도 캐시 세대에 의존한다"는 거짓 신호를 준다. 캐시 세대에 대한 방어는
        // 캐시 모드를 검증하는 PlacePopularFlowIT의 책임이다.
    }

    @Test
    void 인기순은_점수_내림차순이고_커서_페이징은_항목을_흘리지도_겹치지도_않는다() {
        PlaceFilterGetResponse page1 = placeService.getPlaces(me, popularRequest(null, 2));

        assertThat(ids(page1)).containsExactly(placeC, placeA);   // ≈6.0 > ≈4.0
        assertThat(page1.nextCursor()).isNotNull();

        PlaceFilterGetResponse page2 =
                placeService.getPlaces(me, popularRequest(page1.nextCursor(), 2));

        assertThat(ids(page2)).containsExactly(placeB);           // 2.5
        assertThat(page2.nextCursor()).isNull();
        // 전 페이지 합집합 = 전체, 교집합 = 공집합 (누락 0 · 중복 0)
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));
    }

    /**
     * 최신순도 db 모드에서는 DB가 서빙한다 ({@code places} 기준, 카운트만 place_stats LEFT JOIN).
     *
     * <p><b>인기순과 동네를 나눈 이유.</b> 최신순 픽스처를 같은 동네에 심으면 그 장소들이 인기순
     * 결과에도 0점으로 끼어들어 위 테스트의 기대값([C, A] → [B])이 통째로 흔들린다. 원본과 같은
     * 기대값을 유지하는 것이 이 파일의 목적이므로, 축을 늘리는 대신 무대를 나눴다.
     *
     * <p><b>픽스처 설계.</b> 원본의 A·B·C는 전부 같은 초에 생성돼 id 타이브레이크만 검증된다.
     * 그래서 여기서는 (1) 같은 초 3건으로 <b>id 내림차순</b> 타이브레이크를, (2) id는 가장 크지만
     * 생성일은 가장 이른 1건으로 <b>생성일 내림차순</b>이 id보다 우선함을 함께 문다 —
     * 시각 축이 빠지면 {@code lOld}가 맨 앞으로 올라와 즉시 깨진다.
     *
     * <p>페이지 경계를 <b>동점 구간 한가운데</b>에 두는 것이 핵심이다. 커서가 싣는 값은 초 단위뿐이라
     * ({@code PlaceListCursor}의 sortKey 한계) 등호 분기의 id 타이브레이크가 없으면 같은 초의
     * {@code l1}이 통째로 누락되고, 반대로 경계가 {@code <=}로 느슨해지면 {@code l2}가 중복된다.
     *
     * <p>이 장소들은 setUp의 배치 <b>이후</b>에 심으므로 place_stats 행이 없다 — 최신순이
     * {@code LEFT JOIN}으로 카운트를 붙이는 경로(신규 장소가 맨 앞에 와야 한다)까지 함께 걷는다.
     */
    @Test
    void 최신순은_생성일_내림차순이고_동점은_id_내림차순이며_커서가_같은_초를_흘리지_않는다() {
        long latestTownId = createTown(LATEST_TOWN_NAME);
        long l1 = createPlace(latestTownId, "db직행최신1", PLACE_CREATED_AT);
        long l2 = createPlace(latestTownId, "db직행최신2", PLACE_CREATED_AT);
        long l3 = createPlace(latestTownId, "db직행최신3", PLACE_CREATED_AT);
        long lOld = createPlace(latestTownId, "db직행최신0", PLACE_CREATED_AT.minusDays(2));

        PlaceFilterGetResponse page1 =
                placeService.getPlaces(me, latestRequest(latestTownId, null, 2));

        assertThat(ids(page1)).containsExactly(l3, l2);   // 같은 초 → id 내림차순
        assertThat(page1.nextCursor()).isNotNull();

        PlaceFilterGetResponse page2 =
                placeService.getPlaces(me, latestRequest(latestTownId, page1.nextCursor(), 2));

        // l1: 커서와 같은 초에 남은 항목 / lOld: id는 최대지만 2일 이르므로 맨 뒤
        assertThat(ids(page2)).containsExactly(l1, lOld);
        assertThat(page2.nextCursor()).isNull();
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));

        // place_stats 행이 없는 신규 장소는 0건으로 읽는다 (INNER JOIN이면 여기서 사라진다)
        assertThat(previewOf(page2, lOld).bookmarkCount()).isZero();
    }

    /**
     * <b>북마크 검색 — 정렬 축이 두 개다.</b> {@code latest}는 <em>내가 북마크한 순서</em>이고
     * {@code popular}는 <em>장소 점수 순서</em>다. 둘이 같은 답을 내는 픽스처로는 어느 한쪽이
     * 통째로 빠져도 그린이므로, 두 순서가 <b>반드시 달라지게</b> 세운다:
     *
     * <pre>
     *   장소   점수(배치)   내 북마크 시각        latest 순위   popular 순위
     *   A      ≈4.0        기준 +60분 (가장 최근)     1            2
     *   C      ≈6.0        기준 +30분                 2            1
     *   B       2.5        기준 −90일 (가장 오래)     3            3
     * </pre>
     *
     * <p>A·B는 setUp에서 온 것이고(B는 배치 전 북마크, C는 배치 후 직접 INSERT),
     * A만 이 테스트가 더한다 — 세 장소의 북마크 시각이 전부 갈리게 만드는 마지막 조각이다.
     *
     * <p><b>페이징은 적용하지 않는다.</b> 유저당 상한이 작다는 전제 위의 계약이라
     * size를 줘도 잘리지 않고 nextCursor는 항상 null이다. 목록 경로의 규칙을 이 경로에
     * 잘못 이식하면 여기서 즉시 깨진다.
     */
    @Test
    void 북마크_검색_최신순은_내가_북마크한_순서다() {
        insertBookmark(me, placeA, CALCULATED_AT.plusMinutes(60));

        PlaceFilterGetResponse response =
                placeService.getPlaces(me, bookmarkRequest(PlaceSortType.LATEST, null, 2));

        assertThat(ids(response)).containsExactly(placeA, placeC, placeB);
        // size=2를 줘도 3건이 그대로 나온다 — 이 경로는 페이징을 타지 않는다
        assertThat(response.nextCursor()).isNull();
        // 전부 내 북마크라 여부는 구조적으로 확정 — 조회 없이 true여야 한다
        assertThat(response.places()).allMatch(PlacePreviewDto::isBookmarked);
        // 표시 카운트는 place_stats 값 그대로 (C는 배치 후 직접 INSERT라 0)
        assertThat(previewOf(response, placeA).bookmarkCount()).isEqualTo(4);
        assertThat(previewOf(response, placeB).bookmarkCount()).isEqualTo(5);
        assertThat(previewOf(response, placeC).bookmarkCount()).isZero();
    }

    /**
     * 같은 픽스처에 정렬만 바꾼다 — 순서가 위와 달라야 한다(C가 A를 앞선다).
     * 규칙은 목록 경로 {@code findPopularRows}의 ORDER BY와 같은 (점수 DESC, id ASC)이며,
     * 점수 정렬이 빠지면 북마크 최신순([A, C, B])이 그대로 나와 즉시 드러난다.
     */
    @Test
    void 북마크_검색_인기순은_점수_내림차순이다() {
        insertBookmark(me, placeA, CALCULATED_AT.plusMinutes(60));

        PlaceFilterGetResponse response =
                placeService.getPlaces(me, bookmarkRequest(PlaceSortType.POPULAR, null, null));

        assertThat(ids(response)).containsExactly(placeC, placeA, placeB);   // ≈6.0 > ≈4.0 > 2.5
        assertThat(response.nextCursor()).isNull();
    }

    /**
     * 태그 필터가 이 경로에도 걸린다 — 요청의 태그가 {@code PlaceTagMatcher}까지 실제로
     * 전달되는지가 검증 대상이다(의미론 자체는 {@code PlaceTagMatcherTest}의 몫).
     * 배선이 끊기면 필터가 무시돼 세 장소가 전부 나온다.
     *
     * <p>태그를 붙이는 대상을 <b>점수 1위가 아닌 placeA</b>로 고르는 것이 핵심이다 —
     * 1위에 붙이면 "필터가 빠졌는데 정렬 덕분에 맨 앞이 맞는" 상태와 구분이 안 된다.
     */
    @Test
    void 북마크_검색에도_태그_필터가_적용된다() {
        insertBookmark(me, placeA, CALCULATED_AT.plusMinutes(60));
        long mainTagId = createMainTag();
        linkTag(placeA, mainTagId);

        PlaceFilterGetResponse response =
                placeService.getPlaces(me, bookmarkRequest(PlaceSortType.POPULAR, mainTagId, null));

        assertThat(ids(response)).containsExactly(placeA);
    }

    /**
     * 표시 카운트는 place_stats 값 그대로다 — 조회 경로가 더하거나 빼지 않는다.
     * db 모드에서는 정렬 쿼리가 실어 온 {@code ps.bookmark_count}가 그 값이고, 캐시 모드에서는
     * 요청 시점 {@code findViewsByPlaceIds}가 읽은 값이다 — <b>출처가 달라도 결과는 같아야 한다.</b>
     *
     * <p>placeC가 이 계약을 무는 자리다. setUp이 배치 <b>이후에</b> 내 북마크를 <em>직접 INSERT</em>해
     * 두므로(이벤트를 거치지 않아 증분도 없다) place_stats는 0인데 {@code isBookmarked}는 true다.
     * 예전의 표시 보정이라면 1을 냈다. 보정을 되살리면 여기가 0 대신 1이 되어 깨진다.
     */
    @Test
    void 표시_카운트는_place_stats_값을_가공_없이_내보낸다() {
        PlaceFilterGetResponse page = placeService.getPlaces(me, popularRequest(null, 3));

        PlacePreviewDto c = previewOf(page, placeC);
        PlacePreviewDto a = previewOf(page, placeA);
        PlacePreviewDto b = previewOf(page, placeB);

        // C: 배치가 센 값은 0. 내 북마크가 배치 이후지만 응답은 보정하지 않는다
        assertThat(c.bookmarkCount()).isZero();
        assertThat(c.isBookmarked()).isTrue();
        // A: 남의 북마크 4건 그대로, 내 북마크 없음
        assertThat(a.bookmarkCount()).isEqualTo(4);
        assertThat(a.isBookmarked()).isFalse();
        // B: 내 북마크가 배치 이전이라 이미 배치값 5에 포함
        assertThat(b.bookmarkCount()).isEqualTo(5);
        assertThat(b.isBookmarked()).isTrue();
    }

    /**
     * 증분의 실제 이익 — 배치를 기다리지 않고 카운트가 는다. 배치값 4에서 1건을 더 누르면 5다.
     *
     * <p>DB 값(5)과 응답 값(5)을 함께 단언한다. 응답이 6이면 조회 경로가 다시 뭔가를 더하고 있다는
     * 뜻이고, DB가 4에서 멈추면 배선(발행·리스너)이 끊긴 것이다 — 두 실패가 값으로 구분된다.
     *
     * <p>db 모드에는 무효화할 캐시 세대가 없으므로 증분이 DB에 닿는 순간이 곧 응답에 보이는 순간이다.
     * 그래서 {@code awaitUntil} 이후의 기대값이 캐시 모드와 같다 — 같아야만 A/B diff가 성립한다.
     */
    @Test
    void 북마크는_배치를_기다리지_않고_증분으로_반영된다() throws Exception {
        long userNew = createUser();
        assertThat(bookmarkCountInDb(placeA)).isEqualTo(4);   // 사전 조건을 값으로 못 박는다

        bookmarkService.create(userNew, BookmarkTargetType.PLACE, placeA);

        awaitUntil(() -> bookmarkCountInDb(placeA) == 5);

        PlacePreviewDto a = previewOf(placeService.getPlaces(userNew, popularRequest(null, 3)), placeA);
        assertThat(a.bookmarkCount()).isEqualTo(5);
        assertThat(a.isBookmarked()).isTrue();
    }

    /**
     * 취소 즉시 반영이 이 기능을 만든 이유고, 배치 재대사가 그 대가(유실·중복 드리프트)를 갚는 장치다.
     *
     * <p>배치값이 이미 5인 placeB를 쓰면 첫 {@code awaitUntil}이 증분 도달 전에 참이 되어 아무것도
     * 검증하지 못한다. 배치값 4인 placeA로 4→5→4를 본다.
     */
    @Test
    void 취소는_즉시_반영되고_배치_재실행은_증분_드리프트를_재대사한다() throws Exception {
        long userNew = createUser();
        bookmarkService.create(userNew, BookmarkTargetType.PLACE, placeA);
        awaitUntil(() -> bookmarkCountInDb(placeA) == 5);

        bookmarkService.delete(userNew, BookmarkTargetType.PLACE, placeA);

        awaitUntil(() -> bookmarkCountInDb(placeA) == 4);   // 취소 즉시 반영 — 증분의 실이익

        // 원본에 없는 한 줄: db 모드는 감분이 DB에 닿으면 그대로 응답이 된다. DB만 보면 조회 경로가
        // 감분을 무시하고 다른 값을 싣는 회귀를 못 잡는다.
        PlacePreviewDto a =
                previewOf(placeService.getPlaces(userNew, popularRequest(null, 3)), placeA);
        assertThat(a.bookmarkCount()).isEqualTo(4);
        assertThat(a.isBookmarked()).isFalse();

        // 재대사: 원본 기준으로 다시 세면 증분이 남긴 흔적과 무관하게 같은 값에 수렴한다
        batchProcessor.recalculateAll(CALCULATED_AT.plusDays(1));
        assertThat(bookmarkCountInDb(placeA)).isEqualTo(4);
    }

    /**
     * {@code BookmarkService.create}의 {@code type == PLACE} 발행 가드를 문다.
     * {@code bookmarks.target_id}는 PLACE와 COURSE가 숫자 공간을 공유하므로, 가드를 지우면
     * 코스 북마크가 <b>같은 id의 장소</b> 카운트를 올린다.
     *
     * <p>{@code awaitUntil}이 아니라 고정 대기인 이유: 검증 대상이 "도달함"이 아니라
     * <b>"도달하지 않음"</b>이라 기다릴 조건이 없다. 500ms는 같은 스위트의 증분이 수십 ms 안에
     * 반영되는 것을 관측한 데서 잡은 여유다.
     */
    @Test
    void 코스_북마크는_같은_id_장소의_카운트를_증분하지_않는다() throws Exception {
        createCourseWithId(placeA);   // validatorRegistry가 존재를 검사하므로 실제 행이 필요하다
        int before = bookmarkCountInDb(placeA);

        bookmarkService.create(createUser(), BookmarkTargetType.COURSE, placeA);
        Thread.sleep(500);

        assertThat(bookmarkCountInDb(placeA)).isEqualTo(before);
    }

    // === helpers ===

    /**
     * {@code @Async} 증분이 반영될 때까지 폴링. 5초 타임아웃 — 실패 시 그 자체가 배선 단절의 증거다
     * (리스너의 {@code @TransactionalEventListener}나 발행 한 줄이 빠지면 여기서 걸린다).
     * awaitility를 새로 들이지 않는 것은 이 한 곳에서만 쓰기 때문이다.
     */
    private void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                org.junit.jupiter.api.Assertions.fail("5초 내 비동기 증분 미반영");
            }
            Thread.sleep(50);
        }
    }

    /** place_stats의 원시 카운트. 행이 없으면 −1 (기대값과 절대 겹치지 않는 센티널) */
    private int bookmarkCountInDb(long placeId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT bookmark_count FROM place_stats WHERE place_id = ?", Integer.class, placeId);
        return rows.isEmpty() ? -1 : rows.get(0);
    }

    /**
     * 장소와 <b>같은 id</b>의 코스를 만든다 — 그래야 target_id 혼동을 재현할 수 있다.
     * courses.id는 AUTO_INCREMENT지만 명시 지정이 가능하다. 이미 그 id의 코스가 있으면
     * (시드 데이터와 충돌한 경우) 그대로 두고 쓴다 — 뒷정리는 town_id 기준이라 남의 행을 지우지 않는다.
     */
    private void createCourseWithId(long id) {
        jdbcTemplate.update("""
                INSERT INTO courses (id, name, introduction, is_shared, town_id, active, created_at)
                VALUES (?, 'db직행IT코스', 'db직행IT', true, ?, true, ?)
                ON DUPLICATE KEY UPDATE id = id""", id, townId, PLACE_CREATED_AT);
    }

    private PlaceFilterGetRequest popularRequest(String cursor, Integer size) {
        return new PlaceFilterGetRequest(
                townId, false, null, null, null, PlaceSortType.POPULAR, cursor, size);
    }

    private PlaceFilterGetRequest latestRequest(long town, String cursor, Integer size) {
        return new PlaceFilterGetRequest(
                town, false, null, null, null, PlaceSortType.LATEST, cursor, size);
    }

    /** 북마크 검색은 커서를 발급하지 않으므로 인자에도 두지 않는다 (size는 무시됨을 보이려고 남긴다) */
    private PlaceFilterGetRequest bookmarkRequest(PlaceSortType sort, Long mainTagId, Integer size) {
        return new PlaceFilterGetRequest(
                townId, true, mainTagId, null, null, sort, null, size);
    }

    private List<Long> ids(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeId).toList();
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

    private long createPlace(long town, String name, LocalDateTime createdAt) {
        // created_by는 DEFAULT 1 — V2 시드의 admin 유저(id=1)라 FK가 성립한다
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, 'db직행IT', ?, true, ?)""", name, town, createdAt);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    // static인 이유: 이 IT는 커밋을 남기고(@Transactional 롤백 없음) JUnit은 테스트마다 새 인스턴스를
    // 만들므로, 인스턴스 필드면 두 번째 테스트의 setUp이 같은 nickname을 또 넣어 UNIQUE에 걸린다
    private static int userSeq = 0;

    /** tags.name에 UNIQUE는 없지만, 뒷정리가 이름으로 되찾으므로 회차마다 갈라 둔다 (userSeq와 같은 이유로 static) */
    private static int tagSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /**
     * 태그 필터 배선 검증용 MAIN 태그. {@code TagValidator.validatePlaceTagConditions}가
     * active·usage=PLACE·type=MAIN을 요구하므로 셋 다 맞춰 심는다 (서브 태그가 없으면 거기서 통과).
     * 이름 접두사는 {@code @AfterAll}이 되찾는 유일한 기준점이다.
     */
    private long createMainTag() {
        String name = TAG_NAME_PREFIX + (++tagSeq);
        jdbcTemplate.update("""
                INSERT INTO tags (name, type, parent_id, active, tag_usage)
                VALUES (?, 'MAIN', NULL, true, 'PLACE')""", name);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tags WHERE name = ?", Long.class, name);
    }

    private void linkTag(long placeId, long tagId) {
        jdbcTemplate.update(
                "INSERT INTO place_tag (place_id, tag_id) VALUES (?, ?)", placeId, tagId);
    }

    private void insertBookmark(long userId, long placeId, LocalDateTime createdAt) {
        // uk_bookmark_user_target (user_id, target_type, target_id) — 같은 장소 2건이면 유저가 달라야 함
        jdbcTemplate.update("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (?, 'PLACE', ?, ?, ?)""", userId, placeId, createdAt, createdAt);
    }

    /** 컬럼 목록은 PlaceStatsBatchProcessorIT.insertReview의 INSERT 문과 같다 (NOT NULL 전부 포함) */
    private void insertReview(long userId, long placeId, int rating, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'EVENING', 'db직행 검증용 리뷰 본문입니다.', ?, ?, ?)""",
                userId, placeId, createdAt.toLocalDate(), rating, createdAt, createdAt);
    }

    /**
     * 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다.
     * 픽스처 역추적의 기준점은 towns.name — 거기서 places, 그 places의 bookmarks·place_reviews로 내려간다.
     * 이름 접두사가 원본 사슬 IT("사슬IT…")와 다르므로 두 IT는 서로의 픽스처를 지우지 않는다.
     *
     * <p><b>place_stats만 전량 삭제하는 이유.</b> {@code recalculateAll}은 내 장소가 아니라
     * <b>모든 장소</b>에 행을 남긴다. 그 행들을 남겨두면 place_stats가 비어 있음을 전제로 하는
     * 다른 IT들이 깨진다 — 현재는 {@code PlaceStatsRepositoryIT}가 그렇다. 배치가 만든 행은 전부
     * 이 테스트가 만든 것이므로 전량 삭제가 곧 "내가 만든 것만 삭제"다
     * ({@code PlaceStatsBatchProcessorIT}·{@code PlacePopularFlowIT}도 같은 이유로 같은 정리를 한다).
     *
     * <p><b>{@code @AfterAll} + {@code DriverManager}인 이유는 두 가지뿐이다.</b> (a) {@code @AfterAll}은
     * static이라 {@code @Autowired JdbcTemplate}에 닿을 수 없어 커넥션을 직접 연다, (b) 클래스당 1회가
     * 메서드당보다 싸다. 이 클래스는 테스트 트랜잭션 자체가 없어({@code @SpringBootTest},
     * {@code @Transactional} 없음) 모든 쓰기가 이미 커밋돼 있으므로 두 번째 커넥션이 락을 기다릴 일도 없다.
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns =
                "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'PLACE' AND target_id IN ("
                            + myPlaces + ")");
            // 코스 북마크: createCourseWithId가 장소와 같은 id로 코스를 만들므로 target_id도 내 장소 id다.
            // 남기면 뒤의 users DELETE가 fk_bookmarks_user에 걸려 뒷정리 전체가 실패한다.
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'COURSE' AND target_id IN ("
                            + myPlaces + ")");
            st.executeUpdate("DELETE FROM place_reviews WHERE place_id IN (" + myPlaces + ")");
            // courses는 towns를 FK로 참조하므로 towns보다 먼저 지운다. town_id 기준이라
            // 우연히 같은 id를 갖는 시드 코스는 건드리지 않는다.
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            // place_tag는 places FK가 ON DELETE CASCADE라 places 삭제로 함께 사라진다(V7).
            // 남는 것은 tags 행 자체뿐이라 그것만 이름으로 되찾아 지운다.
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate(
                    "DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
