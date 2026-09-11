package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.facade.AdminPlaceFacade;
import org.sopt.solply_server.domain.admin.place.service.AdminPlaceService;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.service.BookmarkService;
import org.sopt.solply_server.domain.place.cache.SnapshotLoader;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 장소 목록 <b>유일 경로</b>의 사슬 IT — 북마크·리뷰 INSERT → 배치 → 조회 → 정렬 → 커서 왕복까지
 * 걷는다. 조각별 테스트(배치 IT·쿼리 IT·커서 단위 테스트)는 이음새를 못 지키는데, 이 기능의 실제
 * 버그 2건(LATEST 커서 누락, 표시 이중 계산)이 전부 이음새에서 났다.
 *
 * <p>덮는 정렬 축은 여섯이고(POPULAR·LATEST·RATING·REVIEW_COUNT·BOOKMARK_COUNT·DISTANCE),
 * 커서 왕복·필터 지문 거부도 여기서 사슬 수준으로 문다. 북마크 검색(페이징 없는 별도 조립)도
 * 같은 무대에서 걷는다.
 *
 * <p><b>setUp 픽스처 하나가 새 정렬 넷의 무대를 겸한다.</b> 인기 점수를 만들려고 심은 북마크·리뷰가
 * 그대로 카운트 축과 평점 축의 값이 되고(리뷰 5건씩 두 장소 · 북마크 5건과 4건 · 평점 5.00과 1.00),
 * 덕분에 각 정렬의 순서가 <b>서로 다르게</b> 나온다 — 같은 순서를 내는 픽스처에서는 정렬 분기가
 * 통째로 뒤바뀌어도 전부 그린이다.
 *
 * <p><b>두 배치를 항상 함께 돌리는 것이 이 파일의 픽스처 규약이다.</b> 카운트 회차가 표시 값을
 * 채우고 점수 회차가 채점하므로, 하나만 돌리면 "행은 있는데 전부 0점"이 되어 순위 단언이 통째로
 * id 순으로 흐른다. 두 배치가 서로의 칸을 침범하지 않는다는 것은
 * {@code PlaceStatsBatchProcessorIT}의 소유권 테스트가 따로 문다.
 *
 * <p><b>행 자체는 배치가 만들지 않는다.</b> 어드민 경로로 만든 장소는 그 트랜잭션이 행을 짓고,
 * DB 직행 픽스처는 {@link #createPlace}가 같은 자리를 채운다.
 *
 * <p><b>사슬에 한 마디가 늘었다 — 회차다 (#397).</b> 조회가 읽는 곳은 목록 스냅샷 하나뿐이고,
 * DB의 변경은 <b>다음 회차가 스냅샷을 다시 찍을 때</b> 목록에 나타난다. 그래서 이 파일의 DB 직행
 * 픽스처는 "쓰기 → 배치 → <b>{@link #takeSnapshot()}</b> → 조회"로 걷고, 운영에서 그 자리를 채우는
 * 것은 10분 주기 타이머다({@code SnapshotScheduler}). <b>여기서 회차를 생략하면 조회가
 * 픽스처 이전의 스냅샷을 보므로, 회차를 부르지 않은 단언은 곧 "낡은 스냅샷을 본다"는 주장이다.</b>
 *
 * <p><b>어드민 경로만은 예외이고, 그 예외가 검증 대상이다.</b> 어드민 쓰기는 자기 커밋 뒤에
 * 스스로 스냅샷을 다시 찍으므로({@code SnapshotRefresher}) 아래 어드민 시나리오들은
 * {@code takeSnapshot()}을 <b>일부러 부르지 않는다</b> — 부르는 순간 "훅이 찍은 것"인지 "손으로
 * 찍은 것"인지 구분되지 않아, 훅을 통째로 떼도 전부 그린이 된다.
 *
 * <p><b>계약: 단언은 PlaceService 응답 DTO 수준으로만 한다.</b> 내부 표현(네이티브 SQL의 컬럼 순서,
 * 레포지토리 record 모양)이 바뀌는 리팩터링에서 이 파일은 수정 없이 그린이어야 한다.
 */
@SpringBootTest
class PlaceListFlowIT extends MySqlContainerSupport {

    /**
     * 메서드 이름은 베이스의 {@code datasource}와 반드시 달라야 한다.
     * {@code @DynamicPropertySource}는 static이라 같은 이름이면 상위/동명 메서드를 <em>숨겨</em>
     * 설정이 통째로 사라진다.
     *
     * <p><b>배치 스케줄 셋을 모두 꺼야 하는 이유.</b> {@code @SpringBootTest}는 실제 앱을 띄우므로
     * {@code PlaceStatsFacade}의 {@code @Scheduled} 셋이 그대로 등록된다. 카운트는 매시 30분이라
     * 스위트가 어느 시간대에 돌든 그 순간을 지나면 스케줄러가 {@code now()} 기준으로 배치를 돌려
     * 픽스처가 의존하는 place_stats를 덮어쓴다. {@code "-"}는 스프링이 "등록하지 않음"으로
     * 해석하는 센티널이다 ({@code Scheduled.CRON_DISABLED}).
     */
    @DynamicPropertySource
    static void listFlowProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    @Autowired private PlaceService placeService;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    /** 회차를 손으로 돌린다 — 운영에서 이 자리를 채우는 것은 10분 주기 타이머다 */
    @Autowired private SnapshotLoader snapshotLoader;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 실제 북마크 생성 경로. 리포지토리를 직접 부르면 서비스 층의 계약이 검증에서 빠진다. */
    @Autowired private BookmarkService bookmarkService;
    /** 어드민의 실제 생성·수정·삭제 경로. 컨트롤러가 부르는 진입점이라 파사드로 잡는다. */
    @Autowired private AdminPlaceFacade adminPlaceFacade;
    /** 동네 재활성은 파사드에 없다 — {@code AdminTownService}가 이 메서드를 직접 부른다 */
    @Autowired private AdminPlaceService adminPlaceService;
    /** DB 직행으로 places·place_tag를 고친 픽스처가 어드민 쓰기의 나머지 한 걸음을 대신할 때 쓴다 */
    @Autowired private PlaceStatsRepository placeStatsRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** 픽스처의 장소 생성 시각. 세 장소가 같은 초라 POPULAR 쪽은 시각 축을 쓰지 않는다 */
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);

    /**
     * 뒷정리가 이 테스트의 픽스처를 역추적하는 유일한 기준점. 같은 싱글턴 컨테이너를 쓰는 다른
     * IT의 접두사와 겹치면 먼저 끝난 쪽의 {@code @AfterAll}이 아직 도는 쪽의 픽스처를 지운다.
     * ({@code _}·{@code %}가 들어가면 아래 LIKE에서 와일드카드가 되므로 넣지 않는다.)
     */
    private static final String TOWN_NAME_PREFIX = "db직행IT동네";

    /** 인기순 픽스처가 사는 동네 */
    private static final String POPULAR_TOWN_NAME = TOWN_NAME_PREFIX + "인기";

    /** 최신순 픽스처가 사는 동네 — 인기순 동네와 나누는 이유는 최신순 테스트의 주석 참조 */
    private static final String LATEST_TOWN_NAME = TOWN_NAME_PREFIX + "최신";

    /** users.nickname UNIQUE — 배치 IT('배치테스트유저')와 겹치지 않는 접두사 */
    private static final String USER_NICKNAME_PREFIX = "db직행IT유저";

    /** 태그 필터 배선 검증용 태그의 이름 접두사 — 뒷정리가 이것으로 되찾는다 */
    private static final String TAG_NAME_PREFIX = "db직행IT태그";

    /** V2 시드의 admin 유저 */
    private static final long ADMIN_USER_ID = 1L;

    /**
     * 어드민 쓰기 경로가 쓰는 태그 좌표는 <b>V2 시드의 것을 그대로 빌린다</b>.
     * {@code TagValidator}가 "서브 태그의 parent = 메인 태그"까지 요구하므로 계층이 이미 맞는
     * 시드를 쓰는 편이, 관계까지 갖춘 태그 세 개를 매번 심는 것보다 픽스처가 짧다.
     * (1 = 카페 · 7 = 커피/디저트 · 8 = 작업, 뒤 둘은 OPTION1이고 parent가 1이다.)
     */
    private static final long SEED_MAIN_TAG = 1L;
    private static final long SEED_OPTION1_A = 7L;
    private static final long SEED_OPTION1_B = 8L;

    // 점수는 ln(1 + 감쇠합) + 2 × (조정평점 − 전체평균)이고, 여기서 전체평균 C는 3.0이다
    // (5점 5건 + 1점 5건). 북마크 쪽이 ≈인 것은 감쇠항이 "기준시각 1분 전"에도 미세하게
    // 걸리기 때문이다(실측: A의 감쇠합 3.999979).
    //
    // ⚠️ 리뷰를 두 장소에 나눠 넣는 것은 취향이 아니라 필수다. 조정 평점의 기준 C가 전체 리뷰의
    // 평균이라, 리뷰가 한 장소에만 있으면 C가 그 장소의 평균과 같아져 리뷰 항이 통째로 0이 된다.
    // 실제로 이 픽스처는 placeC에 5점 1건만 있었고, 그 상태에서 placeC가 꼴찌로 떨어졌다.
    private long townId;
    private long placeA;   // 기준시각 1분 전 남의 북마크 4건 → 점수 ≈ 1.609434
    private long placeB;   // 90일 전 북마크 5건(남 4 + 나 1) + 1점 리뷰 5건 → 점수 ≈ −0.747237
    private long placeC;   // 5점 리뷰 5건 → 점수 = 2.0
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
        // placeB의 1점 리뷰가 전체 평균 C를 3.0으로 붙든다 — 없으면 C가 placeC의 평균과
        // 같아져 placeC의 리뷰 항이 0이 되고, 리뷰만으로 1위라는 이 픽스처의 전제가 무너진다.
        for (int i = 0; i < 5; i++) {
            insertReview(createUser(), placeC, 5, CALCULATED_AT.minusMinutes(1));
            insertReview(createUser(), placeB, 1, CALCULATED_AT.minusMinutes(1));
        }

        // 내 북마크지만 배치 "이전" — 배치가 이미 센 쪽이다 (placeB 카운트 5의 다섯 번째)
        insertBookmark(me, placeB, CALCULATED_AT.minusDays(90));

        runBothBatches(CALCULATED_AT);

        // 내 북마크지만 배치 "이후"라 이번 회차의 카운트에는 없다 —
        // place_stats는 0인데 isBookmarked는 true인 상태를 만든다.
        // 표시 보정이 되살아나면 이 조합에서만 카운트가 1로 부풀어 즉시 잡힌다.
        insertBookmark(me, placeC, CALCULATED_AT.plusMinutes(30));

        // 픽스처를 다 심은 뒤 스냅샷을 찍는다 — 조회는 이 회차만 본다
        takeSnapshot();
    }

    @Test
    void 인기순은_점수_내림차순이고_커서_페이징은_항목을_흘리지도_겹치지도_않는다() {
        PlaceFilterGetResponse page1 = placeService.getPlaces(me, popularRequest(null, 2));

        assertThat(ids(page1)).containsExactly(placeC, placeA);   // 2.0 > ≈1.609
        assertThat(page1.nextCursor()).isNotNull();

        PlaceFilterGetResponse page2 =
                placeService.getPlaces(me, popularRequest(page1.nextCursor(), 2));

        assertThat(ids(page2)).containsExactly(placeB);           // ≈−0.747
        assertThat(page2.nextCursor()).isNull();
        // 전 페이지 합집합 = 전체, 교집합 = 공집합 (누락 0 · 중복 0)
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));
    }

    /**
     * 최신순도 {@code place_stats}를 기준 테이블로 DB가 서빙한다 (V34).
     *
     * <p><b>인기순과 동네를 나눈 이유.</b> 최신순 픽스처를 같은 동네에 심으면 그 장소들이 인기순
     * 결과에도 0점으로 끼어들어 위 테스트의 기대값([C, A] → [B])이 통째로 흔들린다.
     * 인기순 기대값을 지키려고 축을 늘리는 대신 무대를 나눴다.
     *
     * <p><b>픽스처 설계.</b> 인기순 무대의 A·B·C는 전부 같은 초에 생성돼 id 타이브레이크만 검증된다.
     * 그래서 여기서는 (1) 같은 초 3건으로 <b>id 내림차순</b> 타이브레이크를, (2) id는 가장 크지만
     * 생성일은 가장 이른 1건으로 <b>생성일 내림차순</b>이 id보다 우선함을 함께 문다 —
     * 시각 축이 빠지면 {@code lOld}가 맨 앞으로 올라와 즉시 깨진다.
     *
     * <p>페이지 경계를 <b>동점 구간 한가운데</b>에 두는 것이 핵심이다. 커서가 싣는 값은 초 단위뿐이라
     * ({@code PlaceListCursor}의 sortKey 한계) 등호 분기의 id 타이브레이크가 없으면 같은 초의
     * {@code l1}이 통째로 누락되고, 반대로 경계가 {@code <=}로 느슨해지면 {@code l2}가 중복된다.
     *
     * <p>이 장소들은 SQL로 직접 심어 어드민 경로를 지나치므로, 최신순에 나오려면 카운트 배치가
     * 한 번 돌아야 한다 (V34: 최신순의 기준 테이블이 place_stats다). 어드민 경로로 만든 장소가
     * 배치 없이 즉시 나오는 것은 {@link #어드민이_만든_장소는_배치_없이_최신순에_즉시_나온다}가 문다.
     */
    @Test
    void 최신순은_생성일_내림차순이고_동점은_id_내림차순이며_커서가_같은_초를_흘리지_않는다() {
        long latestTownId = createTown(LATEST_TOWN_NAME);
        long l1 = createPlace(latestTownId, "db직행최신1", PLACE_CREATED_AT);
        long l2 = createPlace(latestTownId, "db직행최신2", PLACE_CREATED_AT);
        long l3 = createPlace(latestTownId, "db직행최신3", PLACE_CREATED_AT);
        long lOld = createPlace(latestTownId, "db직행최신0", PLACE_CREATED_AT.minusDays(2));
        batchProcessor.recalculateCounts(CALCULATED_AT.plusHours(1));
        takeSnapshot();

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

        // 북마크가 없는 장소는 0건으로 읽는다
        assertThat(previewOf(page2, lOld).bookmarkCount()).isZero();
    }

    /**
     * <b>어드민의 삭제가 목록에서 장소를 빼는 유일한 경로다.</b> 두 정렬 모두 place_stats가 기준
     * 테이블이고("행이 있으면 목록에 나와도 되는 장소"가 불변식) 두 배치 어느 쪽도 행을 지우지
     * 않으므로, 이 경로가 빠지면 내린 장소가 영구히 노출된다.
     *
     * <p><b>삭제 뒤에는 배치도 회차도 손으로 돌리지 않는 것이 요점이다.</b> 끼우면 장소가 사라진
     * 이유가 "삭제가 행을 지우고 커밋 훅이 스냅샷을 다시 찍어서"인지 "손으로 돌린 것 때문"인지
     * 구분되지 않는다.
     *
     * <p>이 장소에 북마크를 달지 말 것 — {@code bookmarks}는 다형 {@code target_id}라 places에
     * FK가 없어 장소를 지워도 남고, 그 잔행이 {@code @AfterAll}의 users 삭제를
     * {@code fk_bookmarks_user}로 막는다.
     */
    @Test
    void 어드민이_삭제한_장소는_배치를_기다리지_않고_인기순에서_사라진다() {
        long doomed = createPlace(townId, "db직행삭제", PLACE_CREATED_AT);
        runBothBatches(CALCULATED_AT.plusHours(1));
        takeSnapshot();

        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 10)))).contains(doomed);
        assertThat(statsRowExists(doomed)).isTrue();

        adminPlaceFacade.deletePlace(doomed);

        // 행은 그 자리에서 사라지고, 커밋 훅이 찍은 새 스냅샷에도 없다
        assertThat(statsRowExists(doomed)).isFalse();
        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 10))))
                .doesNotContain(doomed);
    }

    /**
     * <b>신규 장소는 점수 배치를 기다리지 않는다 — 카운트 배치가 행을 만든 순간 인기순에 든다.</b>
     *
     * <p>그 행의 {@code popular_score}는 컬럼 기본값 0이고, 인기순은 그 값 그대로 정렬한다.
     * 여기서는 <b>사슬 전체가 그 결정을 지키는지</b>를 본다 — 배치 → 회차 → 조회 → 응답까지.
     *
     * <p><b>같은 무대에서 표시 카운트도 정상이어야 한다.</b> 순위에 드는 것과 "통계가 제대로
     * 실리는 것"은 다른 말이라 최신순 응답의 카운트까지 함께 문다.
     *
     * <p>점수 배치를 뒤이어 돌리는 것은 <b>채점이 자리를 흔들지 않음</b>을 남기기 위해서다 —
     * 북마크 1건짜리 신규 장소는 채점 뒤에도 placeA 아래·placeB 위 그대로다.
     */
    @Test
    void 신규_장소는_점수_배치_전에도_인기순에_들고_최신순_카운트에도_나온다() {
        long newPlace = createPlace(townId, "db직행신규", CALCULATED_AT.plusMinutes(5));
        insertBookmark(createUser(), newPlace, CALCULATED_AT.plusMinutes(10));

        // 카운트 배치만 — 행은 생기고 점수 칸은 0에 머문다
        batchProcessor.recalculateCounts(CALCULATED_AT.plusHours(1));
        takeSnapshot();

        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 10))))
                .containsExactly(placeC, placeA, newPlace, placeB);
        // 같은 시점의 최신순에는 맨 앞에 뜨고, 카운트 배치가 센 값이 그대로 실린다
        PlaceFilterGetResponse latest = placeService.getPlaces(me, latestRequest(townId, null, 10));
        assertThat(ids(latest)).startsWith(newPlace);
        assertThat(previewOf(latest, newPlace).bookmarkCount()).isEqualTo(1);

        // 점수 배치가 돌면 0이 실제 점수(북마크 1건 ≈0.69)로 바뀌지만 자리는 그대로다
        batchProcessor.recalculateScores(CALCULATED_AT.plusHours(1));
        takeSnapshot();

        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 10))))
                .containsExactly(placeC, placeA, newPlace, placeB);
    }

    /**
     * <b>점수 0이 유효한 음수 점수를 앞선다 — 사슬 수준의 확인.</b>
     *
     * <p>픽스처의 placeB는 1점 리뷰 5건이 붙어 점수가 <em>음수</em>({@code ≈−0.747})다. 아직 아무
     * 평가도 받지 않은 신규 장소는 0이므로 그 위에 서고, 응답 순서가 {@code [C, A, 신규, B]}가
     * 된다 — "평가 없음"이 "평가 나쁨"보다 위라는 것이 이 정렬이 고른 순서다(스펙 결정 2026-09-01).
     *
     * <p>앞 테스트와 달리 신규 장소에 북마크를 달지 않는다 — 점수 0이 <b>아직 채점 전이라서</b>여야
     * 이 순서가 0의 자리를 말하는 것이 된다.
     */
    @Test
    void 점수가_없는_신규_장소가_음수_점수_장소를_앞선다() {
        long newPlace = createPlace(townId, "db직행음수대조", CALCULATED_AT.plusMinutes(5));
        batchProcessor.recalculateCounts(CALCULATED_AT.plusHours(1));
        takeSnapshot();

        List<Long> ranked = ids(placeService.getPlaces(me, popularRequest(null, 10)));

        assertThat(ranked).containsExactly(placeC, placeA, newPlace, placeB);
        // 꼬리는 여전히 음수 점수 장소다 — 0이 그보다 아래로 내려가면 여기가 newPlace로 바뀐다
        assertThat(ranked.get(ranked.size() - 1)).isEqualTo(placeB);
    }

    // === 어드민 쓰기 경로가 place_stats를 동기 유지한다 (V34) ===

    /**
     * <b>어드민 쓰기는 커밋 <em>뒤에</em> 목록 스냅샷을 다시 짓는다.</b>
     *
     * <p>커밋 전에 지으면 로더의 새 커넥션이 아직 커밋되지 않은 변경을 보지 못해 <b>옛 데이터</b>로
     * 스냅샷을 짓고, 그 낡은 스냅샷이 다음 트리거까지 남는다 — 방금 만든 장소가 목록에서 사라지고
     * 방금 지운 장소가 계속 나온다. 이 테스트가 정확히 그 시점을 문다: 재생성이 커밋보다 앞서면
     * 아래 첫 단언이, 훅이 아예 없으면 둘 다 빨개진다.
     *
     * <p><b>생성과 삭제를 한 무대에서 걷는 이유.</b> 두 지점이 어드민이 스냅샷의 원천을 바꾸는
     * 경로의 전부이고({@code AdminPlaceService}의 {@code syncPlaceStats}와 {@code deletePlace}),
     * 한쪽만 보면 나머지 훅이 빠져도 그린이다.
     *
     * <p>배치도 손으로 찍는 회차도 한 번도 쓰지 않는다 — 끼우는 순간 이 두 단언이 "커밋 훅이 한
     * 일"을 보는 것인지 아닌지가 구분되지 않는다.
     */
    @Test
    void 어드민_쓰기는_커밋_뒤에_목록_스냅샷을_다시_짓는다() {
        long adminTownId = createTown(TOWN_NAME_PREFIX + "어드민커밋훅");

        long created = adminPlaceFacade.createPlace(
                ADMIN_USER_ID, upsertRequest("db직행어드민커밋훅", adminTownId, SEED_OPTION1_A)).placeId();

        assertThat(ids(placeService.getPlaces(me, latestRequest(adminTownId, null, 10))))
                .containsExactly(created);

        adminPlaceFacade.deletePlace(created);

        assertThat(ids(placeService.getPlaces(me, latestRequest(adminTownId, null, 10)))).isEmpty();
    }

    /**
     * <b>어드민이 만든 장소는 배치를 기다리지 않는다.</b> V34로 최신순의 기준 테이블이
     * place_stats가 되면서, 행을 안 만들면 방금 등록한 장소가 <em>최신순 맨 앞</em>에서 최대
     * 1시간 사라진다 — 대가로 수용할 수 없는 종류의 창이라 생성 경로가 같은 트랜잭션에서 행을 짓는다.
     *
     * <p><b>태그 필터를 걸어 조회하는 것이 요점이다.</b> 행만 생기고 {@code tag_bitmask}가 0이면
     * 무필터 조회는 통과하고 태그 조회만 조용히 비는데, 그 상태가 정확히 이 마이그레이션의
     * 대표적 실패 모양이다.
     *
     * <p><b>배치도 회차도 한 번도 돌리지 않는다.</b> 끼우는 순간 이 장소가 목록에 뜬 이유가
     * "어드민 트랜잭션이 행을 짓고 커밋 훅이 스냅샷을 다시 찍어서"인지 "손으로 돌린 것 때문"인지
     * 구분되지 않는다.
     */
    @Test
    void 어드민이_만든_장소는_배치_없이_최신순에_즉시_나온다() {
        long adminTownId = createTown(TOWN_NAME_PREFIX + "어드민생성");

        long created = adminPlaceFacade.createPlace(
                ADMIN_USER_ID, upsertRequest("db직행어드민생성", adminTownId, SEED_OPTION1_A)).placeId();

        assertThat(ids(placeService.getPlaces(me, latestTagRequest(adminTownId, SEED_OPTION1_A))))
                .containsExactly(created);
    }

    /**
     * <b>인기순에도 즉시 나온다 — 위 테스트의 짝이다.</b> 어드민이 만든 행은 아직 채점 전이라
     * 점수가 0인데, 인기순은 그 값 그대로 정렬하므로 다음 점수 배치를 기다릴 이유가 없다
     * (스펙 결정 2026-09-01). 채점 여부를 묻는 술어가 되살아나면 여기가 즉시 빈다.
     *
     * <p>최신순 짝과 같은 이유로 배치도 회차도 돌리지 않고, 같은 이유로 태그 필터를 걸어 조회한다.
     */
    @Test
    void 어드민이_만든_장소는_배치_없이_인기순에도_즉시_나온다() {
        long adminTownId = createTown(TOWN_NAME_PREFIX + "어드민생성인기");

        long created = adminPlaceFacade.createPlace(
                ADMIN_USER_ID, upsertRequest("db직행어드민생성인기", adminTownId, SEED_OPTION1_A)).placeId();

        assertThat(ids(placeService.getPlaces(me, popularTagRequest(adminTownId, SEED_OPTION1_A))))
                .containsExactly(created);
    }

    /**
     * <b>태그를 갈아 끼우면 필터 결과가 그 자리에서 갈린다.</b> {@code tag_bitmask}는 place_tag의
     * 사본이라, 수정 경로가 다시 짓지 않으면 <b>뗀 태그로 계속 검색되고 새로 붙인 태그로는 안
     * 잡히는</b> 상태가 다음 배치까지 남는다. 두 방향을 함께 단언하는 이유가 그것이다 —
     * 한쪽만 보면 마스크를 지우기만 하고 다시 채우지 않는 변이가 통과한다.
     */
    @Test
    void 어드민의_태그_수정은_배치_없이_필터에_즉시_반영된다() {
        long adminTownId = createTown(TOWN_NAME_PREFIX + "어드민태그수정");
        long placeId = adminPlaceFacade.createPlace(
                ADMIN_USER_ID, upsertRequest("db직행어드민수정", adminTownId, SEED_OPTION1_A)).placeId();

        adminPlaceFacade.updatePlace(
                placeId, upsertRequest("db직행어드민수정", adminTownId, SEED_OPTION1_B));

        assertThat(ids(placeService.getPlaces(me, latestTagRequest(adminTownId, SEED_OPTION1_A))))
                .isEmpty();
        assertThat(ids(placeService.getPlaces(me, latestTagRequest(adminTownId, SEED_OPTION1_B))))
                .containsExactly(placeId);
    }

    /**
     * <b>동네를 옮기면 소속도 그 자리에서 옮겨간다.</b> {@code place_stats.town_id}는 정렬 인덱스의
     * 선두 컬럼이라 places에서 비정규화해 온 값이고, 낡으면 순위가 아니라 <em>소속</em>이 틀린다 —
     * 옮긴 장소가 옛 동네 목록에 계속 낀다. 배치 간격이 그 창의 상한이던 것을 V34의 동기 갱신이 닫았다.
     */
    @Test
    void 어드민의_동네_이동은_배치_없이_목록_소속에_즉시_반영된다() {
        long fromTownId = createTown(TOWN_NAME_PREFIX + "어드민이동전");
        long toTownId = createTown(TOWN_NAME_PREFIX + "어드민이동후");
        long placeId = adminPlaceFacade.createPlace(
                ADMIN_USER_ID, upsertRequest("db직행어드민이동", fromTownId, SEED_OPTION1_A)).placeId();

        adminPlaceFacade.updatePlace(
                placeId, upsertRequest("db직행어드민이동", toTownId, SEED_OPTION1_A));

        assertThat(ids(placeService.getPlaces(me, latestRequest(fromTownId, null, 10)))).isEmpty();
        assertThat(ids(placeService.getPlaces(me, latestRequest(toTownId, null, 10))))
                .containsExactly(placeId);
    }

    /**
     * <b>되살린 장소도 배치를 기다리지 않는다.</b> 내리는 쪽만 즉시로 당기고 되살리는 쪽은 배치에
     * 맡기던 옛 비대칭은 인기순만 place_stats를 기준으로 삼던 시절의 것이다. 최신순까지 같은 기준이
     * 된 지금 행을 안 만들면 되살린 장소가 <em>최신순에서도</em> 최대 1시간 사라진다.
     *
     * <p>비대칭은 이제 남지 않는다 — 새로 만든 행은 아직 채점 전이지만 인기순도 점수 값 그대로
     * 정렬하므로 0점 자리에 함께 돌아온다. 두 정렬을 여기서 나란히 확인한다.
     *
     * <p>"사라진 상태"는 행을 직접 지워 만든다 — 어드민의 삭제 경로가 하는 일과 같고, 배치는
     * 행의 존재에 관여하지 않으므로 회차를 아무리 돌려도 이 상태가 만들어지지 않는다.
     *
     * <p><b>지운 직후 회차를 한 번 찍는 것은 사라진 상태를 스냅샷에까지 새기기 위해서다.</b> 안 찍으면
     * 옛 스냅샷이 이 장소를 그대로 들고 있어, 마지막 단언이 "재활성이 되살렸다"가 아니라 "옛 스냅샷에
     * 남아 있었다"로 통과한다 — 재활성 훅을 통째로 떼도 그린인 테스트가 된다. 그 뒤로는 배치도
     * 회차도 손으로 돌리지 않는다.
     */
    @Test
    void 재활성화된_장소는_배치_없이_두_정렬에_즉시_돌아온다() {
        long revivedTownId = createTown(TOWN_NAME_PREFIX + "어드민재활성");
        long placeId = createPlace(revivedTownId, "db직행재활성", PLACE_CREATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT.plusHours(1));
        takeSnapshot();
        assertThat(ids(placeService.getPlaces(me, latestRequest(revivedTownId, null, 10))))
                .containsExactly(placeId);

        jdbcTemplate.update("UPDATE places SET active = false WHERE id = ?", placeId);
        jdbcTemplate.update("DELETE FROM place_stats WHERE place_id = ?", placeId);
        assertThat(statsRowExists(placeId)).isFalse();
        takeSnapshot();
        assertThat(ids(placeService.getPlaces(me, latestRequest(revivedTownId, null, 10))))
                .isEmpty();

        jdbcTemplate.update("UPDATE places SET active = true WHERE id = ?", placeId);
        adminPlaceService.activatePlacesByTownIds(List.of(revivedTownId));

        assertThat(ids(placeService.getPlaces(me, latestRequest(revivedTownId, null, 10))))
                .containsExactly(placeId);
        // 되살아난 행은 아직 채점 전이지만 인기순에도 0점 자리로 함께 돌아온다
        assertThat(ids(placeService.getPlaces(me, popularRequest(revivedTownId, null, 10))))
                .containsExactly(placeId);
    }

    // === 커서 v4: 좌표와 필터 지문 ===

    /**
     * <b>커서는 좌표(정렬 키 + id)와 지문만 싣는다.</b> v3까지 있던 세대 필드가 사라졌으므로,
     * 다음 페이지가 참조하는 것은 발급 당시의 <em>순위 좌표</em>뿐이다.
     *
     * <p>발급된 커서의 정렬 키가 <b>앞 페이지 마지막 항목의 점수</b>와 같아야 다음 페이지의 경계가
     * 성립한다 — 값이 어긋나면 항목이 흘리거나 겹치는데 그것은 200 응답이라 조용하다.
     */
    @Test
    void 커서는_앞_페이지_마지막_항목의_좌표를_싣는다() {
        PlaceFilterGetResponse page1 = placeService.getPlaces(me, popularRequest(null, 2));
        assertThat(ids(page1)).containsExactly(placeC, placeA);   // 2.0 > ≈1.609

        PlaceListCursor issued = PlaceListCursor.decode(page1.nextCursor());

        assertThat(issued.sort()).isEqualTo(PlaceSortType.POPULAR);
        assertThat(issued.placeId()).isEqualTo(placeA);
        // placeA의 점수 ≈1.609434 — 커서가 placeC(2.0)의 좌표를 실으면 placeA가 다음 페이지에 중복된다
        assertThat(issued.key(0)).isCloseTo(1.609434, within(0.00001));
    }

    /**
     * <b>스크롤 도중 스냅샷이 교체되면 그 커서는 명시 만료다 (커서 v6).</b>
     *
     * <p>여기는 하루 전까지 <b>수용한 중복</b>을 값으로 남기던 자리다. 커서가 좌표만 싣던 시절에는
     * 회차가 바뀌면 2페이지가 <em>새</em> 좌표계에서 재개돼, 점수가 미세하게 내려앉은 placeA가
     * 1페이지에 이어 또 나왔다(≈1.609434 → ≈1.609412). 새벽 배치라 마주칠 확률이 희박하다는 것이
     * 그때의 근거였는데, 스냅샷을 10분마다 다시 찍는 지금은 그 창이 <b>상시</b>가 되어 수용할 수 없다.
     * 그래서 커서가 자기 회차를 싣고 다니고, 서버는 지금 잡은 스냅샷이 <b>그 회차인지</b>만 본다.
     *
     * <p><b>답은 "옛 회차로 이어 서빙"이 아니라 "끊기"다.</b> 홀더가 최신 한 장만 들기 때문이고,
     * 그것은 <b>어드민 변경을 곧바로 보여주되 옛 회차로 스크롤을 이어 주지는 않는다</b>는 정책
     * 그대로다({@code SnapshotBox} 계약 4). 중복·누락을 막는 방식이 "옛 좌표계 유지"에서
     * "명시 만료"로 바뀐 것이지, 조용한 오답을 허용한 것이 아니다.
     *
     * <p>placeB에 북마크를 몰아 넣는 것은 2회차 순위를 실제로 흔들기 위해서다 — 두 회차가 똑같으면
     * 만료가 났는지 우연히 같은 답을 냈는지 구분되지 않는다.
     * (그래도 placeB가 1위가 되지는 않는다. 1점 리뷰 5건의 페널티가 −2.0으로 붙어 있다.)
     *
     * <p>마지막 단언이 <b>복구 경로</b>다. 커서 없는 재요청은 새 회차의 순서를 그대로 줘야 한다 —
     * 만료가 스크롤을 끊는 것이지 목록을 막는 것이 아니라는 것, 그리고 새 스냅샷이 죽어 있지
     * 않다는 것을 함께 본다.
     */
    @Test
    void 스크롤_도중_회차가_바뀌면_커서는_만료로_끊긴다() {
        PlaceFilterGetResponse page1 = placeService.getPlaces(me, popularRequest(null, 2));
        assertThat(ids(page1)).containsExactly(placeC, placeA);
        String cursor = page1.nextCursor();
        assertThat(cursor).isNotNull();

        // 스크롤 도중 회차 1번 — placeB에 북마크 20건을 몰아 순위를 실제로 흔든다
        for (int i = 0; i < 20; i++) {
            insertBookmark(createUser(), placeB, CALCULATED_AT.plusMinutes(10));
        }
        runBothBatches(CALCULATED_AT.plusHours(1));
        takeSnapshot();

        assertThatThrownBy(() -> placeService.getPlaces(me, popularRequest(cursor, 2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);

        // 커서 없는 재요청은 새 회차의 순서를 그대로 준다 — 클라이언트의 복구 경로가 막히지 않았다
        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 3))))
                .containsExactly(placeC, placeA, placeB);
    }

    /**
     * <b>커서를 다른 필터 조합에 재사용하면 명시적 오류다.</b>
     *
     * <p>지문 검증이 없으면 서버는 아무 불평 없이 "새 필터에서 정렬 키 X 아래"를 돌려준다 —
     * 요청한 적 없는 페이지가 200으로 나가고, 클라이언트는 자기가 무엇을 받았는지 알 방법이 없다.
     * 커서를 권위로 삼아 <em>옛 필터</em> 결과를 주는 선택지도 있었지만 그것 역시 조용한 오답이라
     * 기각했다(설계 §5).
     *
     * <p>동네 축과 태그 축을 함께 흔드는 이유는 지문이 <b>축마다</b> 제 몫을 하는지 보기 위해서다 —
     * 한 축만 검증하면 나머지 축이 지문에서 통째로 빠져도 통과한다.
     */
    @Test
    void 커서를_다른_필터_요청에_재사용하면_거부한다() {
        String cursor = placeService.getPlaces(me, popularRequest(null, 2)).nextCursor();
        long otherTownId = createTown(TOWN_NAME_PREFIX + "다른");
        long mainTagId = createMainTag();

        PlaceFilterGetRequest otherTown = new PlaceFilterGetRequest(
                otherTownId, false, null, null, null, PlaceSortType.POPULAR, cursor, 2, null, null);
        PlaceFilterGetRequest otherTag = new PlaceFilterGetRequest(
                townId, false, mainTagId, null, null, PlaceSortType.POPULAR, cursor, 2, null, null);

        assertThatThrownBy(() -> placeService.getPlaces(me, otherTown))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PLACE_CURSOR);
        assertThatThrownBy(() -> placeService.getPlaces(me, otherTag))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PLACE_CURSOR);
    }

    /**
     * <b>손으로 지어낸 커서 토큰은 거부된다.</b> 필드가 하나 모자란 v3 형태를 그대로 흘려보내면
     * 세대 값이 지문 자리로 밀려 들어와 "필터가 다르다"는 엉뚱한 진단이 붙는다 — 코덱에서 끊는
     * 것이 정직하다. 사슬 수준에서 그 응답 코드까지 확인한다.
     */
    @Test
    void 옛_버전의_커서_토큰은_유효하지_않은_커서로_거부한다() {
        String v3Token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v3:POPULAR:2.0:" + placeC + ":1754000000:" + townId + "|||")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> placeService.getPlaces(me, popularRequest(v3Token, 2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PLACE_CURSOR);

        // 커서 없는 재요청은 정상이다 — 오류만 확인하면 "무조건 거부"라는 회귀가 산다
        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 2))))
                .containsExactly(placeC, placeA);
    }

    /**
     * <b>필터 지문 검증은 정렬과 무관하게 동일하게 적용된다.</b> 지문은 배치 주기와 상관없는
     * 구멍(다른 필터 요청에 커서를 재사용)을 막는 장치라, 세대를 걷어낸 뒤에도 두 정렬 모두에 남는다.
     */
    @Test
    void 최신순_커서의_필터_지문도_그대로_검증된다() {
        long latestTownId = createTown(LATEST_TOWN_NAME + "지문");
        createPlace(latestTownId, "db직행지문1", PLACE_CREATED_AT);
        createPlace(latestTownId, "db직행지문2", PLACE_CREATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT.plusHours(1));
        takeSnapshot();

        String cursor =
                placeService.getPlaces(me, latestRequest(latestTownId, null, 1)).nextCursor();

        // 발급 지문은 요청한 동네의 것이다 — 다른 동네 요청에 그대로 쓰면 거부된다
        assertThat(PlaceListCursor.decode(cursor).filterPrint())
                .isEqualTo(latestTownId + "|||");
        assertThatThrownBy(() -> placeService.getPlaces(me, latestRequest(townId, cursor, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PLACE_CURSOR);
    }

    // === 정렬 확장 4종 (2026-08-16) ===

    /**
     * <b>평점 높은 순.</b> setUp 픽스처가 그대로 이 정렬의 무대가 된다 —
     * placeC는 5점 5건(평점 5.00), placeB는 1점 5건(평점 1.00), placeA는 리뷰가 없어 평점이 0이다.
     *
     * <p>그래서 이 한 테스트가 두 가지를 함께 문다: 평점 내림차순이라는 순서와, <b>리뷰가 없는
     * 장소(placeA)가 0점으로 맨 뒤에 실린다</b>는 계약(V37). 하루 전 스펙은 정반대로 그 장소를
     * 목록에서 끊었고, 뒤집은 것은 프로덕트 판단이다.
     *
     * <p>페이지 크기를 1로 두어 커서가 실제로 발급·소비되게 한다 — 커서 키가 (평점, 리뷰 수) 둘인
     * 유일한 정렬이라, 한 칸만 실리면 여기서 두 번째 페이지가 비거나 첫 항목이 되돌아온다.
     * 마지막 페이지는 <b>커서로 0점 구간에 실제로 진입</b>하는 자리이기도 하다.
     *
     * <p>마지막 단언이 <b>표시 계약</b>이다 — 저장이 0이 된 뒤에도 응답의 평점은 여전히 null이다.
     * 화면에서 "평점 0점"과 "아직 평가 없음"은 다른 말이고, 그 되돌림은 {@code PlacePreviewDto#of}가 한다.
     */
    @Test
    void 평점순은_평점_내림차순이고_리뷰_없는_장소는_0점으로_맨_뒤에_실린다() {
        PlaceFilterGetResponse page1 =
                placeService.getPlaces(me, sortRequest(townId, PlaceSortType.RATING, null, 1));

        assertThat(ids(page1)).containsExactly(placeC);
        assertThat(page1.nextCursor()).isNotNull();
        // 커서가 두 칸(평점 5.00, 리뷰 5건)을 싣는다 — 동점 구간에서 seek이 재개될 좌표다
        assertThat(PlaceListCursor.decode(page1.nextCursor()).sortKeys())
                .containsExactly(5.0, 5.0);

        PlaceFilterGetResponse page2 = placeService.getPlaces(
                me, sortRequest(townId, PlaceSortType.RATING, page1.nextCursor(), 1));

        assertThat(ids(page2)).containsExactly(placeB);
        assertThat(page2.nextCursor()).isNotNull();

        PlaceFilterGetResponse page3 = placeService.getPlaces(
                me, sortRequest(townId, PlaceSortType.RATING, page2.nextCursor(), 1));

        assertThat(ids(page3)).containsExactly(placeA);
        assertThat(page3.nextCursor()).isNull();
        assertThat(previewOf(page3, placeA).avgRating()).isNull();
        assertThat(previewOf(page3, placeA).reviewCount()).isZero();
    }

    /**
     * <b>리뷰 많은 순.</b> placeB·placeC가 나란히 5건이라 <b>1위 자리가 동점</b>이고, 그 경계를
     * 페이지가 가른다 — 타이브레이크(id ASC)가 없거나 등호 분기가 빠지면 placeC가 통째로 누락되거나
     * 두 페이지에 겹쳐 나온다. 리뷰가 없는 placeA는 0건으로 맨 뒤에 남는다 — 평점순도 V37부터
     * 같은 규칙이라, 이제 두 정렬 모두 리뷰 0건 장소를 끊지 않는다.
     */
    @Test
    void 리뷰순은_리뷰수_내림차순이고_동점_경계에서_항목을_흘리지_않는다() {
        PlaceFilterGetResponse page1 =
                placeService.getPlaces(me, sortRequest(townId, PlaceSortType.REVIEW_COUNT, null, 1));

        assertThat(ids(page1)).containsExactly(placeB);   // 동점 5건 중 id가 작은 쪽

        PlaceFilterGetResponse page2 = placeService.getPlaces(
                me, sortRequest(townId, PlaceSortType.REVIEW_COUNT, page1.nextCursor(), 2));

        assertThat(ids(page2)).containsExactly(placeC, placeA);
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));
    }

    /**
     * <b>북마크 많은 순 — 인기순과 다른 축이다.</b> 픽스처의 북마크 수는 B 5건 · A 4건 · C 0건인데
     * 인기 점수 순서는 C · A · B라, 두 정렬이 같은 SQL을 쓰면 순서가 정확히 뒤집혀 드러난다.
     * 그 대비를 한 테스트 안에서 함께 단언한다.
     *
     * <p>placeC에 대한 내 북마크는 배치 <em>이후</em>라 이번 회차 카운트에 없다 — 정렬도 표시값과
     * 같은 place_stats 값을 보므로 0건 취급이 맞다.
     */
    @Test
    void 북마크순은_누적_북마크수_내림차순이고_인기순과_순서가_다르다() {
        PlaceFilterGetResponse byBookmark = placeService.getPlaces(
                me, sortRequest(townId, PlaceSortType.BOOKMARK_COUNT, null, 3));

        assertThat(ids(byBookmark)).containsExactly(placeB, placeA, placeC);
        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 3))))
                .containsExactly(placeC, placeA, placeB);
    }

    /** 새 정렬도 커서의 필터 지문 검증을 그대로 받는다 — 지문은 정렬과 직교한 장치다 */
    @Test
    void 새_정렬의_커서도_다른_필터_요청에_재사용하면_거부한다() {
        String cursor = placeService
                .getPlaces(me, sortRequest(townId, PlaceSortType.BOOKMARK_COUNT, null, 1))
                .nextCursor();
        long otherTownId = createTown(TOWN_NAME_PREFIX + "북마크순");

        assertThatThrownBy(() -> placeService.getPlaces(
                me, sortRequest(otherTownId, PlaceSortType.BOOKMARK_COUNT, cursor, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PLACE_CURSOR);
    }

    /**
     * <b>거리순은 기준점 없이 성립하지 않는다.</b> 좌표가 없으면 400(PLACE-005)으로 끊는다 —
     * 임의의 기준점(동네 중심 등)을 지어내면 "가까운 순"이라는 말이 사용자 위치와 무관해진다.
     *
     * <p>위도만 온 요청도 같은 취급이다. 한쪽만으로는 점을 찍을 수 없으므로 "좌표가 없다"와
     * 구분할 이유가 없다.
     *
     * <p>다른 정렬은 좌표를 요구하지 않는다는 것도 함께 확인한다 — 거절 조건이 정렬 밖으로 새면
     * 기존 요청이 통째로 400이 된다.
     */
    @Test
    void 거리순은_좌표가_없으면_거절하고_다른_정렬은_영향받지_않는다() {
        assertThatThrownBy(() ->
                placeService.getPlaces(me, distanceRequest(townId, null, 2, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MISSING_PLACE_COORDINATES);

        assertThatThrownBy(() ->
                placeService.getPlaces(me, distanceRequest(townId, null, 2, 37.5, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MISSING_PLACE_COORDINATES);

        assertThat(ids(placeService.getPlaces(me, popularRequest(null, 2))))
                .containsExactly(placeC, placeA);
    }

    /**
     * <b>거리순의 순서·커서 계약.</b> 기준점에서 가까운 순으로 나오고, 두 번째 페이지는 커서에
     * 박제된 기준 좌표로 이어진다 — 2페이지 요청이 <em>다른</em> 좌표를 보내도(걸어서 이동) 순서가
     * 흔들리지 않아야 한다는 것이 이 정렬의 핵심 계약이다.
     *
     * <p>좌표가 없는 placeC는 어느 페이지에도 나오지 않는다.
     */
    @Test
    void 거리순은_가까운_순이고_커서의_기준_좌표가_파라미터보다_우선한다() {
        // 기준점(37.50, 127.00)에서 A → B → (C는 좌표 없음)
        jdbcTemplate.update(
                "UPDATE places SET latitude = 37.501, longitude = 127.001 WHERE id = ?", placeA);
        jdbcTemplate.update(
                "UPDATE places SET latitude = 37.510, longitude = 127.010 WHERE id = ?", placeB);
        // 좌표도 스냅샷에 실려 있다 — 다시 찍지 않으면 이 요청은 좌표 없던 회차를 본다
        resyncStats(placeA, placeB);
        takeSnapshot();

        PlaceFilterGetResponse page1 = placeService.getPlaces(
                me, distanceRequest(townId, null, 1, 37.50, 127.00));

        assertThat(ids(page1)).containsExactly(placeA);
        assertThat(page1.nextCursor()).isNotNull();

        // 2페이지는 기준점에서 멀찍이 떨어진 좌표를 보낸다 — 무시되고 커서의 기준점이 이긴다
        PlaceFilterGetResponse page2 = placeService.getPlaces(
                me, distanceRequest(townId, page1.nextCursor(), 5, 38.90, 128.90));

        assertThat(ids(page2)).containsExactly(placeB);
    }

    /**
     * <b>북마크 검색 — 정렬 축이 두 개다.</b> {@code latest}는 <em>내가 북마크한 순서</em>이고
     * {@code popular}는 <em>장소 점수 순서</em>다. 둘이 같은 답을 내는 픽스처로는 어느 한쪽이
     * 통째로 빠져도 그린이므로, 두 순서가 <b>반드시 달라지게</b> 세운다:
     *
     * <pre>
     *   장소   점수(배치)   내 북마크 시각        latest 순위   popular 순위
     *   A      ≈1.609      기준 +60분 (가장 최근)     1            2
     *   C       2.0        기준 +30분                 2            1
     *   B      ≈−0.747     기준 −90일 (가장 오래)     3            3
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

        assertThat(ids(response)).containsExactly(placeC, placeA, placeB);   // 2.0 > ≈1.609 > ≈−0.747
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
     * <b>태그가 2개 이상인 장소가 페이지에 있어도 응답은 1건이다.</b>
     *
     * <p>{@code findPlacesWithTagsByIds}가 {@code placeTags}를 컬렉션 페치 조인하므로 태그 2개인
     * 장소는 SQL 행이 2개로 펼쳐진다. 여기 있던 {@code SELECT DISTINCT}는 그 2행을 합치지 못했고
     * (행이 서로 달라 DISTINCT의 대상이 아니다) MySQL 임시 테이블만 하나 깔았으므로 걷어냈다
     * (2026-08-03). 루트 중복 제거는 하이버네이트가 하고, 호출부의 {@code toMap}은 병합 함수로
     * 그 동작에 의존하지 않는다 — 병합 함수가 빠지면 여기서 {@code IllegalStateException:
     * Duplicate key}가 난다.
     *
     * <p><b>지금 그 페치 조인을 타는 것은 북마크 검색 경로뿐이다.</b> 목록 경로는 장소당 엔트리가
     * 하나인 스냅샷을 읽으므로 펼쳐질 행이 없다 — 그래도 두 경로를 한 테스트에서 함께 걷는 이유는,
     * 대표 태그가 <b>둘 중 MAIN 하나로 확정</b>된다는 규칙이 두 경로에 각각 있고(스냅샷을 짓는
     * 파생 테이블 · {@code TagViewUtils}) 한쪽만 고치면 같은 장소가 경로마다 다르게 보이기 때문이다.
     */
    @Test
    void 태그가_두_개인_장소도_목록과_북마크_검색에_한_번만_나온다() {
        long mainTagId = createMainTag();
        long optionTagId = createOptionTag();
        linkTag(placeA, mainTagId);
        linkTag(placeA, optionTagId);
        String mainTagName = jdbcTemplate.queryForObject(
                "SELECT name FROM tags WHERE id = ?", String.class, mainTagId);
        resyncStats(placeA);
        takeSnapshot();

        PlaceFilterGetResponse page = placeService.getPlaces(me, popularRequest(null, 3));

        // 3건 그대로 — placeA가 2행으로 왔지만 응답에는 한 번만 있다
        assertThat(ids(page)).containsExactly(placeC, placeA, placeB);
        // 태그가 두 개여도 대표 태그는 MAIN 하나로 확정된다
        assertThat(previewOf(page, placeA).primaryTag()).isEqualTo(mainTagName);

        // 같은 장소가 북마크 검색 경로에서도 1건이어야 한다 (다른 toMap 호출부)
        insertBookmark(me, placeA, CALCULATED_AT.plusMinutes(60));
        PlaceFilterGetResponse bookmarked =
                placeService.getPlaces(me, bookmarkRequest(PlaceSortType.POPULAR, null, null));

        assertThat(ids(bookmarked)).containsExactly(placeC, placeA, placeB);
    }

    /**
     * 표시 카운트는 place_stats 값 그대로다 — 조회 경로가 더하거나 빼지 않는다.
     * 목록 경로에서는 정렬 쿼리가 실어 온 {@code ps.bookmark_count}가 그 값이다.
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
     * <b>카운트는 배치 전용이다 (증분 폐지).</b> 방금 누른 북마크는 {@code isBookmarked}로
     * 즉시 보이지만 <em>수</em>에는 다음 카운트 배치부터 반영된다 — 신선도 ≤1h를 수용한 결정이다.
     *
     * <p>고정 대기인 이유: 검증 대상이 "도달함"이 아니라 <b>"도달하지 않음"</b>이라 기다릴 조건이
     * 없다. 500ms는 증분이 살아 있던 시절 수십 ms 안에 반영되던 것을 관측한 데서 잡은 여유다.
     * 발행측 이벤트까지 걷어낸 지금은 되살아날 경로가 리스너 <em>와</em> 발행 둘 다인데,
     * 어느 쪽이든 부활하면 여기서 걸린다.
     *
     * <p>이어서 카운트 배치를 돌려 값이 실제로 5가 되는 것까지 본다 — 앞 단언만 두면 "배치도
     * 카운트를 안 센다"는 회귀가 통과한다.
     *
     * <p><b>화면에 닿는 지연은 이제 두 마디다</b> — 배치가 place_stats를 고치고(≤1h), 다음 회차가
     * 그 값을 스냅샷에 옮긴다(≤10분). 아래에서 DB 값과 응답 값을 따로 확인하는 이유가 그것이다.
     */
    @Test
    void 카운트는_배치_전용이라_북마크_직후에는_변하지_않는다() throws Exception {
        long userNew = createUser();
        assertThat(bookmarkCountInDb(placeA)).isEqualTo(4);   // 사전 조건을 값으로 못 박는다

        bookmarkService.create(userNew, BookmarkTargetType.PLACE, placeA);
        Thread.sleep(500);


        assertThat(bookmarkCountInDb(placeA)).isEqualTo(4);
        PlacePreviewDto beforeBatch =
                previewOf(placeService.getPlaces(userNew, popularRequest(null, 3)), placeA);
        assertThat(beforeBatch.bookmarkCount()).isEqualTo(4);
        assertThat(beforeBatch.isBookmarked()).isTrue();   // 체크 표시는 즉시 반영된다

        // 방금 만든 북마크의 created_at은 실제 현재 시각이라, 집계 상한이 그보다 뒤여야 세어진다
        batchProcessor.recalculateCounts(LocalDateTime.now().plusHours(1));

        assertThat(bookmarkCountInDb(placeA)).isEqualTo(5);
        // 배치가 센 값도 다음 회차부터 화면에 닿는다 — 그 전까지는 옛 스냅샷의 4다
        assertThat(previewOf(placeService.getPlaces(userNew, popularRequest(null, 3)), placeA)
                .bookmarkCount()).isEqualTo(4);

        takeSnapshot();

        assertThat(previewOf(placeService.getPlaces(userNew, popularRequest(null, 3)), placeA)
                .bookmarkCount()).isEqualTo(5);
    }

    // === helpers ===

    /**
     * 한 회차 = 카운트 + 점수. 픽스처는 늘 둘을 함께 돌린다 (클래스 javadoc의 규약).
     * 두 회차 모두 <b>이미 있는 행만</b> 갱신하므로 행은 미리 서 있어야 한다.
     */
    private void runBothBatches(LocalDateTime calculatedAt) {
        batchProcessor.recalculateCounts(calculatedAt);
        batchProcessor.recalculateScores(calculatedAt);
    }

    /**
     * 회차 하나 — 지금 DB의 상태로 스냅샷을 다시 찍는다. 운영에서 이 자리를 채우는 것은 10분 주기
     * 타이머이고, 여기서 이 호출을 생략한 조회는 <b>이전 회차의 스냅샷</b>을 본다.
     *
     * <p>회차 버전이 DB 발급 테이블의 AUTO_INCREMENT 번호라({@code SnapshotVersionIssuer})
     * 연달아 두 번 찍어도 두 회차가 같은 번호를 갖지 않는다. 그래서 여기서 회차 사이를 시간으로
     * 벌릴 필요가 없다 — 보존 밖 판정을 세우는 테스트가 그 전제 위에 서 있다.
     */
    private void takeSnapshot() {
        snapshotLoader.rebuild();
    }

    /**
     * 어드민 쓰기가 하는 일 중 DB 직행 픽스처가 건너뛴 걸음 — place_stats의 어드민 소유 칸을 원본에서
     * 다시 짓는다. 이름·좌표·대표 태그가 그 테이블의 칸이 된 뒤로(V40) {@code places}·
     * {@code place_tag}만 고치고 회차를 찍으면 스냅샷이 옛 값을 그대로 본다. 카운트·점수 칸은
     * 이 문장이 건드리지 않으므로 배치가 채워 둔 값이 살아남는다.
     */
    private void resyncStats(long... placeIds) {
        List<Long> ids = Arrays.stream(placeIds).boxed().toList();
        // 쓰기 문장이라 트랜잭션이 있어야 한다 — 이 클래스에는 테스트 트랜잭션이 없다
        transactionTemplate.executeWithoutResult(
                status -> placeStatsRepository.upsertRowsForActivePlaces(ids));
    }

    /** place_stats에 이 장소의 행이 있는가 — 인기순 노출 여부의 물리적 근거다 */
    private boolean statsRowExists(long placeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_stats WHERE place_id = ?", Integer.class, placeId);
        return count != null && count > 0;
    }

    /** place_stats의 원시 카운트. 행이 없으면 −1 (기대값과 절대 겹치지 않는 센티널) */
    private int bookmarkCountInDb(long placeId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT bookmark_count FROM place_stats WHERE place_id = ?",
                Integer.class, placeId);
        return rows.isEmpty() ? -1 : rows.get(0);
    }

    private PlaceFilterGetRequest popularRequest(String cursor, Integer size) {
        return popularRequest(townId, cursor, size);
    }

    private PlaceFilterGetRequest popularRequest(long town, String cursor, Integer size) {
        return sortRequest(town, PlaceSortType.POPULAR, cursor, size);
    }

    /** 좌표 없는 목록 요청. 거리순 외의 정렬은 좌표를 읽지 않는다 */
    private PlaceFilterGetRequest sortRequest(
            long town, PlaceSortType sort, String cursor, Integer size) {
        return new PlaceFilterGetRequest(
                town, false, null, null, null, sort, cursor, size, null, null);
    }

    /** 거리순 요청. 좌표를 null로 주면 "좌표 없는 거리순"이 되어 거절 경로를 밟는다 */
    private PlaceFilterGetRequest distanceRequest(
            long town, String cursor, Integer size, Double lat, Double lng) {
        return new PlaceFilterGetRequest(
                town, false, null, null, null, PlaceSortType.DISTANCE, cursor, size, lat, lng);
    }

    /** 최신순 + 태그 필터. 마스크가 0으로 남는 회귀는 무필터 조회로는 보이지 않는다. */
    private PlaceFilterGetRequest latestTagRequest(long town, long option1TagId) {
        return tagRequest(town, PlaceSortType.LATEST, option1TagId);
    }

    /** {@link #latestTagRequest}의 인기순 짝 */
    private PlaceFilterGetRequest popularTagRequest(long town, long option1TagId) {
        return tagRequest(town, PlaceSortType.POPULAR, option1TagId);
    }

    private PlaceFilterGetRequest tagRequest(long town, PlaceSortType sort, long option1TagId) {
        return new PlaceFilterGetRequest(
                town, false, SEED_MAIN_TAG, List.of(option1TagId), null,
                sort, null, 10, null, null);
    }

    /**
     * 어드민 생성·수정 요청. 이미지 키를 비워 두면 S3 검증이 통째로 건너뛰어지므로
     * ({@code ImageFileKeyValidator}가 목록을 순회할 뿐이다) 이 IT가 S3에 의존하지 않는다.
     */
    private AdminPlaceUpsertRequest upsertRequest(String name, long town, long option1TagId) {
        return new AdminPlaceUpsertRequest(
                name, "db직행 어드민 경로 검증용 소개", "서울시 어딘가", 37.5, 127.0,
                town, SEED_MAIN_TAG, List.of(option1TagId), null,
                List.of(), null, null, null, List.of());
    }

    private PlaceFilterGetRequest latestRequest(long town, String cursor, Integer size) {
        return sortRequest(town, PlaceSortType.LATEST, cursor, size);
    }

    /** 북마크 검색은 커서를 발급하지 않으므로 인자에도 두지 않는다 (size는 무시됨을 보이려고 남긴다) */
    private PlaceFilterGetRequest bookmarkRequest(PlaceSortType sort, Long mainTagId, Integer size) {
        return new PlaceFilterGetRequest(
                townId, true, mainTagId, null, null, sort, null, size, null, null);
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

    /**
     * 장소 하나를 DB에 직행으로 심고 <b>place_stats 행까지 함께</b> 만든다.
     *
     * <p>운영에서 그 행을 만드는 것은 어드민 쓰기 트랜잭션이다
     * ({@code PlaceStatsRepository#upsertRowsForActivePlaces}). 두 배치 어느 쪽도 행을 만들지
     * 않으므로, 어드민 경로를 거치지 않는 이 픽스처가 그 자리를 대신 채워야 한다 — 안 채우면
     * 여기서 만든 장소는 두 정렬 어디에도 나오지 않는다.
     *
     * <p>{@code tag_bitmask}는 0이다. 이 헬퍼는 태그를 달지 않으며, 태그 필터가 걸린 시나리오는
     * 전부 어드민 파사드로 장소를 만든다.
     */
    private long createPlace(long town, String name, LocalDateTime createdAt) {
        // created_by는 DEFAULT 1 — V2 시드의 admin 유저(id=1)라 FK가 성립한다
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, 'db직행IT', ?, true, ?)""", name, town, createdAt);
        long placeId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, tag_bitmask, name, bookmark_count,
                     review_count, avg_rating)
                SELECT p.id, p.town_id, p.created_at, 0, p.name, 0, 0, 0
                FROM places p WHERE p.id = ? AND p.active = 1""", placeId);
        return placeId;
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
        long tagId = nextTagId();
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, 'MAIN', NULL, true, 'PLACE')""", tagId, name);
        return tagId;
    }

    /**
     * <b>태그 id를 auto-increment에 맡기지 않는다.</b> V34부터 태그 id가 곧
     * {@code place_stats.tag_bitmask}의 비트 자리라 62를 넘으면 안 되는데
     * ({@code TagBitmask}), auto-increment 카운터는 롤백해도 되돌아가지 않아 같은 싱글턴 컨테이너를
     * 나눠 쓰는 IT가 늘수록 상한에 다가간다. {@code MAX(id) + 1}은 뒷정리를 따라 되돌아간다.
     */
    private long nextTagId() {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
    }

    /**
     * 한 장소에 두 번째 태그를 달기 위한 OPTION1 태그. 태그 필터를 타지 않는 타입이라
     * (요청의 태그 인자는 MAIN·서브 목록으로 갈린다) 순수하게 "행이 2개로 펼쳐지는" 조건만 만든다.
     */
    private long createOptionTag() {
        String name = TAG_NAME_PREFIX + "옵션" + (++tagSeq);
        long tagId = nextTagId();
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, 'OPTION1', NULL, true, 'PLACE')""", tagId, name);
        return tagId;
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
     *
     * <p><b>place_stats만 전량 삭제하는 이유.</b> 카운트 회차는 내 장소가 아니라
     * <b>모든 장소</b>에 행을 남긴다. 그 행들을 남겨두면 place_stats가 비어 있음을 전제로 하는
     * 다른 IT들이 깨진다 — 현재는 {@code PlaceStatsRepositoryIT}가 그렇다. 배치가 만든 행은 전부
     * 이 테스트가 만든 것이므로 전량 삭제가 곧 "내가 만든 것만 삭제"다
     * ({@code PlaceStatsBatchProcessorIT}도 같은 이유로 같은 정리를 한다).
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
            // 코스 북마크도 내 장소 id 공간에 걸릴 수 있다 — 남기면 뒤의 users DELETE가
            // fk_bookmarks_user에 걸려 뒷정리 전체가 실패한다.
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'COURSE' AND target_id IN ("
                            + myPlaces + ")");
            st.executeUpdate("DELETE FROM place_reviews WHERE place_id IN (" + myPlaces + ")");
            // 이 IT는 BookmarkService를 지나며 카운트 전표를 남긴다. 남기면 뒤에 오는 IT의
            // 소비 회차가 그 전표를 접어 자기 장소의 bookmark_count를 흔든다.
            st.executeUpdate(
                    "DELETE FROM bookmark_count_events WHERE target_id IN (" + myPlaces + ")");
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
