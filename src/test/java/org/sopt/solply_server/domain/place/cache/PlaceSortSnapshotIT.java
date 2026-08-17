package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.admin.place.dto.request.AdminPlaceUpsertRequest;
import org.sopt.solply_server.domain.admin.place.facade.AdminPlaceFacade;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.SortSource;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 후보 A(DB 인덱스 정렬)와 후보 B(인메모리 정렬 스냅샷)의 <b>모드 등가 게이트</b>.
 *
 * <p>두 후보는 같은 창의 부하 캠페인에서 팔 교대로 비교되므로, 판정보다 <em>먼저</em> 통과해야 하는
 * 조건이 하나 있다 — <b>응답 body와 커서 문자열이 같아야 한다</b>. 여기가 빨간 상태로 낸 수치는
 * 서로 다른 응답의 비용을 비교한 것이라 아무 뜻이 없다. ({@code PlaceSkeletonCacheIT}의 세 모드
 * 등가 게이트와 같은 역할·같은 근거다.)
 *
 * <p><b>기대값을 손으로 적지 않는다.</b> 두 모드를 실제로 돌려 서로 비교하는 것이 이 파일의 방식이고,
 * 그래서 순서를 손으로 적어 두 경로가 함께 틀리는 그린이 생기지 않는다. 순서 자체의 정본은
 * {@code PlaceListFlowIT}이 값으로 물고 있다.
 *
 * <p><b>픽스처가 겨누는 갈림길.</b> "대충 맞는" 구현이 통과하지 못하게 정렬마다 함정을 심는다.
 * <ul>
 *   <li><b>동점 구간</b> — 북마크 4건 셋(a1·a2·b1)과 리뷰 3건 둘. 타이브레이크 방향이 틀리거나
 *       seek의 등호 분기가 빠지면 페이지 경계에서 항목이 흘리거나 겹친다.</li>
 *   <li><b>같은 초에 만든 두 장소</b>(a2·a3) — 최신순만 id가 <b>내림차순</b>이라 방향 하나를
 *       베끼면 여기서 갈린다.</li>
 *   <li><b>리뷰 0건</b>(a3·a4·b2) — 평점 0으로 맨 뒤에 실리고 응답에서는 null이다 (V37).</li>
 *   <li><b>미채점 장소</b>(g) — 인기순에서만 빠진다. 술어를 옮기지 않으면 두 모드가 갈린다.</li>
 *   <li><b>좌표 없는 장소</b>(a3) — 거리순 후보에서만 빠진다.</li>
 *   <li><b>동네 둘</b>(C1·C2) — 다중 동네는 DB가 filesort로 만드는 전역 순서를 메모리는 k-way
 *       merge로 만든다. 두 전순서가 같은지가 여기서만 드러난다.</li>
 * </ul>
 */
@SpringBootTest
class PlaceSortSnapshotIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void sortSourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    /** 뒷정리가 픽스처를 역추적하는 유일한 기준점. 다른 IT의 접두사와 겹치면 안 된다 */
    private static final String TOWN_NAME_PREFIX = "정렬소스IT동네";
    private static final String USER_NICKNAME_PREFIX = "정렬소스IT유저";

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** V2 시드의 admin 유저 */
    private static final long ADMIN_USER_ID = 1L;

    /**
     * 어드민 경로가 쓰는 태그는 V2 시드의 것을 그대로 빌린다 ({@code PlaceListFlowIT}과 같은 이유) —
     * {@code TagValidator}가 "서브 태그의 parent = 메인 태그"까지 요구하므로 계층이 맞는 시드가 짧다.
     * 태그 필터 픽스처도 같은 id를 {@code place_tag}에 직접 심어 쓴다.
     */
    private static final long SEED_MAIN_TAG = 1L;
    private static final long SEED_OPTION1_A = 7L;
    private static final long SEED_OPTION1_B = 8L;

    @Autowired private PlaceService placeService;
    @Autowired private PlaceSortSnapshotLoader loader;
    @Autowired private PlaceSortSnapshot snapshot;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private PlaceListProperties placeListProperties;
    @Autowired private AdminPlaceFacade adminPlaceFacade;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 시(root). 이 id로 조회하면 leaf 둘로 확장돼 <b>다중 동네</b> 경로를 탄다 */
    private long cityTownId;
    private long townA;
    private long townB;
    private long me;

    private long a1;   // 5점 리뷰 3건 · 북마크 4 · 태그 {main,7} · 좌표 있음
    private long a2;   // 1점 리뷰 3건 · 북마크 4 · 태그 {main,8} · 좌표 있음
    private long a3;   // 리뷰 0 · 북마크 0 · 태그 없음 · <b>좌표 없음</b> · a2와 같은 초 생성
    private long a4;   // 리뷰 0 · 북마크 2 · 태그 {main,7} · 좌표 있음
    private long unscored;   // 배치 뒤에 생긴 행 — 인기순에서만 빠진다
    private long b1;   // 3점 리뷰 2건 · 북마크 4 · 태그 {main,7} · 다른 동네
    private long b2;   // 리뷰 0 · 북마크 0 · 태그 없음 · 다른 동네

    @BeforeEach
    void setUp() {
        // 회차마다 동네를 새로 만든다 — 이 클래스는 롤백하지 않아 픽스처가 쌓이고,
        // 같은 동네를 재사용하면 페이지 기대(비어 있지 않음·커서 발급)가 회차마다 흔들린다.
        cityTownId = createTown(TOWN_NAME_PREFIX + "시", null);
        townA = createTown(TOWN_NAME_PREFIX + "가", cityTownId);
        townB = createTown(TOWN_NAME_PREFIX + "나", cityTownId);
        me = createUser();

        a1 = createPlace(townA, "정렬소스A", CALCULATED_AT.minusDays(3), 37.5010, 127.0010);
        a2 = createPlace(townA, "정렬소스B", CALCULATED_AT.minusDays(2), 37.5100, 127.0100);
        // a2와 같은 초 — 최신순의 id 내림차순 타이브레이크를 겨눈다. 좌표는 일부러 비운다
        a3 = createPlace(townA, "정렬소스C", CALCULATED_AT.minusDays(2), null, null);
        a4 = createPlace(townA, "정렬소스D", CALCULATED_AT.minusDays(1), 37.5200, 127.0200);
        b1 = createPlace(townB, "정렬소스E", CALCULATED_AT.minusDays(3), 37.4900, 126.9900);
        b2 = createPlace(townB, "정렬소스F", CALCULATED_AT.minusDays(1), 37.4800, 126.9800);

        linkTags(a1, SEED_MAIN_TAG, SEED_OPTION1_A);
        linkTags(a2, SEED_MAIN_TAG, SEED_OPTION1_B);
        linkTags(a4, SEED_MAIN_TAG, SEED_OPTION1_A);
        linkTags(b1, SEED_MAIN_TAG, SEED_OPTION1_A);

        // 북마크 4·4·4·2 — 셋이 동점이라 타이브레이크가 실제로 갈라야 하고, 그 셋이 두 동네에
        // 걸쳐 있어 k-way merge의 경계에서도 같은 판정이 나와야 한다.
        insertBookmark(me, a1);
        for (int i = 0; i < 3; i++) {
            insertBookmark(createUser(), a1);
            insertBookmark(createUser(), a2);
            insertBookmark(createUser(), b1);
        }
        insertBookmark(createUser(), a2);
        for (int i = 0; i < 2; i++) {
            insertBookmark(createUser(), a4);
        }

        for (int i = 0; i < 3; i++) {
            insertReview(createUser(), a1, 5);
            insertReview(createUser(), a2, 1);
        }
        for (int i = 0; i < 2; i++) {
            insertReview(createUser(), b1, 3);
        }

        // 행을 짓는 것은 운영에서 어드민 쓰기 트랜잭션의 몫이다 — 그 경로를 지나치는 이 픽스처는
        // 원본 재구축 문장으로 그 자리를 채운다 (PlaceSkeletonCacheIT과 같은 방식).
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);

        // 채점 <b>뒤에</b> 만든다 — 행은 있고 score_calculated_at은 NULL인 상태가 이 장소의 목적이다
        unscored = createPlace(townA, "정렬소스G", CALCULATED_AT.plusMinutes(5), 37.5300, 127.0300);
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT.plusHours(1));

        loader.rebuild();
    }

    /** 다음 테스트가 기본 팔(DB)에서 시작하도록 되돌린다 — 훅의 모드 판정도 이 값을 본다 */
    @AfterEach
    void restoreSortSource() {
        placeListProperties.setSortSource(SortSource.DB);
    }

    /**
     * <b>정렬 여섯 × 두 페이지가 단일 동네에서 같다.</b> 게이트의 본체다.
     *
     * <p>두 페이지를 도는 이유: 커서는 페이지 <b>마지막 행</b>에서 발급되므로 첫 페이지만 보면
     * 커서 동치가 검증되지 않고, seek 경로(이진 탐색 + 등호 분기)는 두 번째 페이지에서만 돈다.
     */
    @Test
    void 정렬_여섯의_두_페이지가_두_모드에서_같다() {
        for (PlaceSortType sort : PlaceSortType.values()) {
            assertModesAgree(sort + " 단일 동네",
                    cursor -> request(townA, sort, null, cursor, 2));
        }
    }

    /**
     * <b>다중 동네도 같다 — 이 게이트가 가장 얇은 얼음이다.</b> DB는 town별 인덱스 range를 filesort로
     * 합쳐 전역 순서를 만들고, 메모리는 동네별 배열을 힙으로 merge한다. 두 전순서가 같아야 하는데
     * 그것을 보장하는 것은 <b>타이브레이크까지 포함한 비교자</b> 하나뿐이라, 방향이 하나만 어긋나도
     * 동점 구간에서 갈린다 — 픽스처의 북마크 4건 셋이 두 동네에 걸쳐 있는 이유다.
     */
    @Test
    void 다중_동네_조회도_두_모드에서_같다() {
        for (PlaceSortType sort : PlaceSortType.values()) {
            assertModesAgree(sort + " 다중 동네",
                    cursor -> request(cityTownId, sort, null, cursor, 2));
        }
    }

    /**
     * <b>태그 필터를 건 요청도 같다.</b> 마스크가 세 그룹으로 갈려 AND로 엮이는 규칙
     * ({@code TagMasks})을 두 경로가 공유하는지가 여기서 드러난다 — 그룹을 한 마스크로 합치면
     * OR가 되어 결과 집합이 통째로 넓어진다.
     *
     * <p>페이지 크기를 1로 두는 것은 필터 통과 장소가 둘(a1·a4)이라, 커서가 실제로 발급·소비되게
     * 하려면 그래야 하기 때문이다.
     */
    @Test
    void 태그_필터를_건_요청도_두_모드에서_같다() {
        for (PlaceSortType sort : PlaceSortType.values()) {
            assertModesAgree(sort + " 태그 필터",
                    cursor -> request(townA, sort, SEED_OPTION1_A, cursor, 1));
        }
    }

    /**
     * <b>평점 0 구간(리뷰 없음)도 같다.</b> 그 구간은 하루 전까지 술어로 끊겨 있던 자리라
     * (V36 → V37) 규칙이 가장 최근에 뒤집힌 곳이고, 커서가 0점 구간 <em>안으로</em> 진입하는
     * 유일한 경로이기도 하다.
     *
     * <p>등가만 보면 두 모드가 함께 틀려도 그린이므로 표시 계약도 값으로 못 박는다 —
     * 저장은 0이지만 응답의 평점은 여전히 {@code null}이다 ({@code PlacePreviewDto#of}).
     */
    @Test
    void 평점_0_구간도_두_모드에서_같고_응답_평점은_null이다() {
        Function<String, PlaceFilterGetRequest> request =
                cursor -> request(townA, PlaceSortType.RATING, null, cursor, 2);

        PlaceFilterGetResponse page1 = withSortSource(SortSource.DB, () -> get(request.apply(null)));
        assertThat(ids(page1)).containsExactly(a1, a2);   // 5.00 > 1.00

        String cursor = page1.nextCursor();
        PlaceFilterGetResponse dbPage2 =
                withSortSource(SortSource.DB, () -> get(request.apply(cursor)));
        PlaceFilterGetResponse memoryPage2 =
                withSortSource(SortSource.MEMORY, () -> get(request.apply(cursor)));

        // 커서로 0점 구간에 실제로 진입한 페이지다
        assertThat(dbPage2.places()).isNotEmpty();
        assertThat(dbPage2.places()).allMatch(p -> p.reviewCount() == 0);
        assertThat(dbPage2.places()).allMatch(p -> p.avgRating() == null);
        assertThat(memoryPage2).isEqualTo(dbPage2);
    }

    /**
     * <b>미채점 행은 두 모드 모두 인기순에서만 빠진다.</b> 인기순의 유일한 술어
     * ({@code score_calculated_at IS NOT NULL})가 메모리 쪽으로 옮겨졌는지를 값으로 문다 —
     * 등가 비교만으로는 <b>두 경로가 함께 술어를 잃은</b> 상태가 그린이 된다.
     *
     * <p>같은 장소가 최신순에는 즉시 나온다는 비대칭까지 함께 확인해야 "인기순에서만"이 검증된다.
     */
    @Test
    void 미채점_장소는_두_모드_모두_인기순에서만_빠진다() {
        for (SortSource source : SortSource.values()) {
            assertThat(ids(withSortSource(source,
                    () -> get(request(townA, PlaceSortType.POPULAR, null, null, 10)))))
                    .as("%s 인기순", source)
                    .doesNotContain(unscored);
            assertThat(ids(withSortSource(source,
                    () -> get(request(townA, PlaceSortType.LATEST, null, null, 10)))))
                    .as("%s 최신순", source)
                    .contains(unscored);
        }
    }

    /**
     * <b>좌표 없는 장소는 두 모드 모두 거리순에서만 빠진다.</b> 거리를 잴 수 없는 장소를 "무한대"로
     * 뒤에 붙이면 커서 seek이 그 행을 페이지 경계에서 조용히 흘리므로, 두 경로 다 후보 단계에서
     * 끊는다는 것이 계약이다.
     */
    @Test
    void 좌표_없는_장소는_두_모드_모두_거리순에서만_빠진다() {
        for (SortSource source : SortSource.values()) {
            assertThat(ids(withSortSource(source,
                    () -> get(request(townA, PlaceSortType.DISTANCE, null, null, 10)))))
                    .as("%s 거리순", source)
                    .doesNotContain(a3);
            assertThat(ids(withSortSource(source,
                    () -> get(request(townA, PlaceSortType.LATEST, null, null, 10)))))
                    .as("%s 최신순", source)
                    .contains(a3);
        }
    }

    /**
     * <b>어드민 쓰기는 커밋 <em>뒤에</em> 스냅샷을 다시 짓는다.</b>
     *
     * <p>커밋 전에 지으면 로더의 새 커넥션이 아직 커밋되지 않은 변경을 보지 못해 <b>옛 데이터</b>로
     * 사진을 짓고, 그 낡은 사진이 다음 트리거까지 남는다 — 방금 만든 장소가 목록에서 사라지고
     * 방금 지운 장소가 계속 나온다. 이 테스트가 정확히 그 시점을 문다: 재생성이 커밋보다 앞서면
     * 아래 첫 단언이 빨개진다.
     *
     * <p>배치를 한 번도 돌리지 않는 것이 요점이다 — 끼우면 "커밋 훅이 한 일"인지 "배치가 한 일"인지
     * 구분되지 않는다.
     */
    @Test
    void 어드민_쓰기는_커밋_뒤에_정렬_스냅샷을_다시_짓는다() {
        placeListProperties.setSortSource(SortSource.MEMORY);
        long adminTownId = createTown(TOWN_NAME_PREFIX + "어드민", null);

        long created = adminPlaceFacade.createPlace(
                ADMIN_USER_ID, upsertRequest("정렬소스어드민", adminTownId)).placeId();

        assertThat(ids(get(request(adminTownId, PlaceSortType.LATEST, null, null, 10))))
                .containsExactly(created);

        adminPlaceFacade.deletePlace(created);

        assertThat(ids(get(request(adminTownId, PlaceSortType.LATEST, null, null, 10)))).isEmpty();
    }

    /**
     * <b>메모리 모드가 실제로 스냅샷을 읽는다는 증거.</b>
     *
     * <p>위 등가 게이트들은 <em>메모리 팔이 통째로 DB로 새어도</em> 전부 그린이다 — 같은 경로를 두 번
     * 돌린 셈이 되기 때문이다. 그래서 두 팔이 반드시 갈리는 상태를 하나 만든다: 스냅샷을 다시 짓지
     * 않고 {@code place_stats}에만 장소를 더하면, DB 경로는 그 자리에서 보고 메모리 경로는 다음
     * 재생성까지 보지 못한다.
     *
     * <p>그 차이는 버그가 아니라 이 캐시의 정의다 — 스냅샷은 <b>한 회차의 사진</b>이고, 낡음의 상한을
     * 정하는 것은 재생성 트리거 셋(기동·배치·어드민 커밋)이다. 어드민을 지나친 직접 변경만 다음
     * 카운트 회차(≤1h)를 기다린다.
     */
    @Test
    void 메모리_모드는_스냅샷_회차의_사진을_본다() {
        long added = createPlace(townA, "정렬소스H", CALCULATED_AT.plusMinutes(10), 37.5400, 127.0400);
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT.plusHours(2));

        assertThat(ids(withSortSource(SortSource.DB,
                () -> get(request(townA, PlaceSortType.LATEST, null, null, 20)))))
                .as("DB 팔은 즉시 본다").contains(added);
        assertThat(ids(withSortSource(SortSource.MEMORY,
                () -> get(request(townA, PlaceSortType.LATEST, null, null, 20)))))
                .as("메모리 팔이 DB로 새면 여기가 빨개진다").doesNotContain(added);

        loader.rebuild();

        assertThat(ids(withSortSource(SortSource.MEMORY,
                () -> get(request(townA, PlaceSortType.LATEST, null, null, 20)))))
                .as("재생성 뒤에는 본다").contains(added);
    }

    /**
     * <b>스냅샷이 없으면 DB 경로로 되돌아간다 — 빈 목록이 아니다.</b> 정렬 스냅샷이 비면 응답이
     * 느려지는 것이 아니라 <b>틀린다</b>(목록이 통째로 빈다). 그래서 "아직 못 지었다"를 빈 인덱스로
     * 덮지 않고 {@code null}로 남겨 두는 것이 계약이고, 조회 경로가 그 값을 보고 되돌아간다.
     */
    @Test
    void 스냅샷이_없으면_메모리_모드도_DB_경로로_되돌아간다() {
        PlaceFilterGetResponse expected = withSortSource(SortSource.DB,
                () -> get(request(townA, PlaceSortType.LATEST, null, null, 10)));

        snapshot.replace(null);
        assertThat(snapshot.current()).isNull();

        assertThat(withSortSource(SortSource.MEMORY,
                () -> get(request(townA, PlaceSortType.LATEST, null, null, 10))))
                .isEqualTo(expected);
    }

    // === helpers ===

    /**
     * 두 모드의 1·2 페이지가 응답 body와 커서까지 같은지 확인한다.
     *
     * <p><b>비어 있지 않음을 함께 단언하는 것이 핵심이다.</b> 빈 응답 둘은 언제나 같으므로, 그 확인이
     * 없으면 픽스처가 조용히 무너졌을 때 게이트가 공허하게 그린이 된다.
     */
    private void assertModesAgree(String label, Function<String, PlaceFilterGetRequest> request) {
        PlaceFilterGetResponse dbPage1 = withSortSource(SortSource.DB, () -> get(request.apply(null)));
        PlaceFilterGetResponse memoryPage1 =
                withSortSource(SortSource.MEMORY, () -> get(request.apply(null)));

        assertThat(dbPage1.places()).as("%s - 1페이지가 비면 게이트가 공허하다", label).isNotEmpty();
        assertThat(dbPage1.nextCursor()).as("%s - 커서가 없으면 seek 경로를 못 본다", label).isNotNull();
        assertThat(memoryPage1).as("%s - 1페이지 응답", label).isEqualTo(dbPage1);
        assertThat(memoryPage1.nextCursor()).as("%s - 1페이지 커서 토큰", label)
                .isEqualTo(dbPage1.nextCursor());

        String cursor = dbPage1.nextCursor();
        PlaceFilterGetResponse dbPage2 =
                withSortSource(SortSource.DB, () -> get(request.apply(cursor)));
        PlaceFilterGetResponse memoryPage2 =
                withSortSource(SortSource.MEMORY, () -> get(request.apply(cursor)));

        assertThat(dbPage2.places()).as("%s - 2페이지가 비면 seek을 못 본다", label).isNotEmpty();
        assertThat(memoryPage2).as("%s - 2페이지 응답", label).isEqualTo(dbPage2);
        assertThat(memoryPage2.nextCursor()).as("%s - 2페이지 커서 토큰", label)
                .isEqualTo(dbPage2.nextCursor());
        // 두 페이지가 겹치지 않는다 — 등가만 보면 두 경로가 함께 중복을 내도 그린이다
        assertThat(ids(dbPage1)).as("%s - 페이지 중복", label)
                .doesNotContainAnyElementsOf(ids(dbPage2));
    }

    private <T> T withSortSource(SortSource source, Supplier<T> action) {
        placeListProperties.setSortSource(source);
        try {
            return action.get();
        } finally {
            placeListProperties.setSortSource(SortSource.DB);
        }
    }

    private PlaceFilterGetResponse get(PlaceFilterGetRequest request) {
        return placeService.getPlaces(me, request);
    }

    /** 거리순만 좌표를 싣는다 — 다른 정렬은 그 두 값을 읽지 않는다 */
    private PlaceFilterGetRequest request(
            long townId, PlaceSortType sort, Long option1TagId, String cursor, Integer size) {
        boolean distance = sort == PlaceSortType.DISTANCE;
        return new PlaceFilterGetRequest(
                townId, false,
                option1TagId == null ? null : SEED_MAIN_TAG,
                option1TagId == null ? null : List.of(option1TagId),
                null, sort, cursor, size,
                distance ? 37.5000 : null, distance ? 127.0000 : null);
    }

    private AdminPlaceUpsertRequest upsertRequest(String name, long townId) {
        return new AdminPlaceUpsertRequest(
                name, "정렬 스냅샷 트리거 검증용 소개", "서울시 어딘가", 37.5, 127.0,
                townId, SEED_MAIN_TAG, List.of(SEED_OPTION1_A), null,
                List.of(), null, null, null, List.of());
    }

    private List<Long> ids(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeId).toList();
    }

    private long createTown(String name, Long parentId) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, ?, true)", name, parentId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    /** 좌표는 null일 수 있다 — 거리순에서만 갈리는 축이라 그 상태를 픽스처가 들고 있어야 한다 */
    private long createPlace(
            long townId, String name, LocalDateTime createdAt, Double latitude, Double longitude) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at,
                                    latitude, longitude)
                VALUES (?, '정렬소스IT', ?, true, ?, ?, ?)""",
                name, townId, createdAt, latitude, longitude);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    private void linkTags(long placeId, long... tagIds) {
        for (long tagId : tagIds) {
            jdbcTemplate.update(
                    "INSERT INTO place_tag (place_id, tag_id) VALUES (?, ?)", placeId, tagId);
        }
    }

    private static int userSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    private void insertBookmark(long userId, long placeId) {
        LocalDateTime createdAt = CALCULATED_AT.minusDays(1);
        jdbcTemplate.update("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (?, 'PLACE', ?, ?, ?)""", userId, placeId, createdAt, createdAt);
    }

    private void insertReview(long userId, long placeId, int rating) {
        LocalDateTime createdAt = CALCULATED_AT.minusDays(1);
        jdbcTemplate.update("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'EVENING', '정렬 소스 검증용 리뷰 본문입니다.', ?, ?, ?)""",
                userId, placeId, createdAt.toLocalDate(), rating, createdAt, createdAt);
    }

    /** {@code PlaceListFlowIT}과 같은 이유·같은 방식의 뒷정리 (시드 태그는 건드리지 않는다) */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'PLACE' AND target_id IN ("
                            + myPlaces + ")");
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'COURSE' AND target_id IN ("
                            + myPlaces + ")");
            st.executeUpdate("DELETE FROM place_reviews WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            // place_tag는 places FK가 ON DELETE CASCADE라 places 삭제로 함께 사라진다 (V7)
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            // 자식 동네가 부모를 FK로 참조하므로 자식부터 지운다. 자식을 부모 id 서브쿼리로
            // 찾지 않는 이유는 MySQL이 DELETE 대상 테이블을 FROM 절 서브쿼리에 두는 것을 막기
            // 때문이다(ERROR 1093) — 자식도 같은 접두사를 쓰므로 이름으로 곧장 찾는다.
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX
                    + "%' AND parent_id IS NOT NULL");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
