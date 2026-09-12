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
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.CountRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.DistanceCandidateRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.LatestRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.PopularRow;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository.RatingRow;
import org.sopt.solply_server.domain.place.service.PlaceService;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.domain.place.sort.DistanceSort;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.town.util.TownHierarchyResolver;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 통합 스냅샷 경로와 <b>DB 인덱스 정렬 경로</b>의 등가 게이트.
 *
 * <p>조회가 읽는 곳은 이제 스냅샷 하나뿐이고 런타임 스위치는 없다. 그래서 대조 방식이 바뀌었다 —
 * 옛 파일({@code PlaceSortSnapshotIT})은 같은 서비스를 두 모드로 돌려 응답을 비교했지만, 지금은
 * <b>서비스가 낸 페이지</b>와 <b>{@code PlaceListDbQueryRepository}에 같은 입력을 넣어 얻은
 * 페이지</b>를 나란히 놓는다. 그 리포지토리는 이 단계에서 조회 경로 밖으로 나갔으나
 * <b>순서의 정본</b>으로 남아 있다 — 인덱스 정렬·seek 술어를 자바로 옮긴 것이
 * {@code SortedPlaces}이므로, 옮기다 어긋난 자리는 여기서만 드러난다.
 *
 * <p><b>기대값을 손으로 적지 않는다.</b> 두 경로를 실제로 돌려 서로 비교하는 것이 이 파일의
 * 방식이고, 그래서 순서를 손으로 적어 두 경로가 함께 틀리는 그린이 생기지 않는다. 순서 자체의
 * 정본은 {@code PlaceListFlowIT}이 값으로 물고 있다.
 *
 * <p><b>등가만 보면 두 경로가 함께 규칙을 잃어도 그린이므로</b>, 규칙이 걸린 두 자리(점수 없는 행의
 * 자리 · 좌표 없는 장소)는 아래에서 값으로도 못 박는다.
 *
 * <p><b>픽스처가 겨누는 갈림길.</b> "대충 맞는" 구현이 통과하지 못하게 정렬마다 함정을 심는다.
 * <ul>
 *   <li><b>동점 구간</b> — 북마크 4건 셋(a1·a2·b1)과 리뷰 3건 둘. 타이브레이크 방향이 틀리거나
 *       seek의 등호 분기가 빠지면 페이지 경계에서 항목이 흘리거나 겹친다.</li>
 *   <li><b>같은 초에 만든 두 장소</b>(a2·a3) — 최신순만 id가 <b>내림차순</b>이라 방향 하나를
 *       베끼면 여기서 갈린다.</li>
 *   <li><b>같은 평점·다른 리뷰 수</b>(a1 5.00/3건 · a5 5.00/1건) — 평점순만 seek이 2단인데, 그
 *       안쪽 칸이 뒤집혀도 <b>평점 동점이 없는 픽스처에서는 아무 일도 일어나지 않는다</b>(실측으로
 *       확인한 구멍이다). 페이지 경계가 이 동점 구간을 가르므로 방향이 어긋나면 a1이 2페이지에
 *       다시 실린다.</li>
 *   <li><b>리뷰 0건</b>(a3·a4·b2) — 평점 0으로 맨 뒤에 실리고 응답에서는 null이다 (V37).</li>
 *   <li><b>채점 전 장소</b>(unscored)와 <b>음수 점수 장소</b>(a2) — 인기순의 0점 자리가 어디인지가
 *       이 둘로 정해진다. 0점이 둘(a3·unscored)이라 그 동점 구간 안에서 커서 경계도 시험된다.</li>
 *   <li><b>좌표 없는 장소</b>(a3) — 거리순 후보에서만 빠진다.</li>
 *   <li><b>동네 둘</b>(가·나) — 다중 동네는 DB가 filesort로 만드는 전역 순서를 메모리는 k-way
 *       merge로 만든다. 두 전순서가 같은지가 여기서만 드러난다.</li>
 * </ul>
 */
@SpringBootTest
class PlaceListSnapshotEquivalenceIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void equivalenceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        // 매시 회차가 둘로 갈렸다(2026-09-12) — 새 키를 빠뜨리면 :15에 델타 소비가 깨어난다
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    /** 뒷정리가 픽스처를 역추적하는 유일한 기준점. 다른 IT의 접두사와 겹치면 안 된다 */
    private static final String TOWN_NAME_PREFIX = "등가IT동네";
    private static final String USER_NICKNAME_PREFIX = "등가IT유저";

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** 거리순의 기준점. 두 경로에 같은 값이 들어가야 순서를 비교할 수 있다 */
    private static final double REF_LAT = 37.5000;
    private static final double REF_LNG = 127.0000;

    /**
     * 태그 픽스처는 V2 시드의 것을 그대로 빌린다 — {@code TagValidator}가 "서브 태그의 parent =
     * 메인 태그"까지 요구하므로 계층이 맞는 시드가 짧다.
     */
    private static final long SEED_MAIN_TAG = 1L;
    private static final long SEED_OPTION1_A = 7L;
    private static final long SEED_OPTION1_B = 8L;

    @Autowired private PlaceService placeService;
    @Autowired private SnapshotLoader loader;
    @Autowired private PlaceListDbQueryRepository dbQueryRepository;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private TownHierarchyResolver townHierarchyResolver;
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 리포지토리가 {@code EntityManager}를 직접 쓰므로 대조군 호출은 트랜잭션 안에서 돈다 */
    @Autowired private TransactionTemplate transactionTemplate;

    /** 시(root). 이 id로 조회하면 leaf 둘로 확장돼 <b>다중 동네</b> 경로를 탄다 */
    private long cityTownId;
    private long townA;
    private long townB;
    private long me;

    private long a2;         // 1점 리뷰 3건 — 인기 점수가 <b>음수</b>인 유일한 장소다
    private long a3;         // 리뷰 0 · 북마크 0 · 태그 없음 · 좌표 없음 · a2와 같은 초 생성
    private long unscored;   // 채점 뒤에 생긴 행 — 점수 0으로 그 값 위치에 선다

    @BeforeEach
    void setUp() {
        // 회차마다 동네를 새로 만든다 — 이 클래스는 롤백하지 않아 픽스처가 쌓이고,
        // 같은 동네를 재사용하면 페이지 기대(비어 있지 않음·커서 발급)가 회차마다 흔들린다.
        cityTownId = createTown(TOWN_NAME_PREFIX + "시", null);
        townA = createTown(TOWN_NAME_PREFIX + "가", cityTownId);
        townB = createTown(TOWN_NAME_PREFIX + "나", cityTownId);
        me = createUser();

        long a1 = createPlace(townA, "등가A", CALCULATED_AT.minusDays(3), 37.5010, 127.0010);
        a2 = createPlace(townA, "등가B", CALCULATED_AT.minusDays(2), 37.5100, 127.0100);
        // a2와 같은 초 — 최신순의 id 내림차순 타이브레이크를 겨눈다. 좌표는 일부러 비운다
        a3 = createPlace(townA, "등가C", CALCULATED_AT.minusDays(2), null, null);
        long a4 = createPlace(townA, "등가D", CALCULATED_AT.minusDays(1), 37.5200, 127.0200);
        long b1 = createPlace(townB, "등가E", CALCULATED_AT.minusDays(3), 37.4900, 126.9900);
        createPlace(townB, "등가F", CALCULATED_AT.minusDays(1), 37.4800, 126.9800);
        // a1과 평점이 같고 리뷰 수만 다른 둘 — 평점순 2단 seek의 안쪽 칸을 겨눈다.
        // <b>동점이 셋이어야 한다</b>: 둘이면 페이지 경계가 동점 구간의 끝에 놓여, 안쪽 칸이
        // 뒤집혀도 경계 판정이 우연히 같은 자리를 가리킨다(실측으로 확인).
        long a5 = createPlace(townA, "등가H", CALCULATED_AT.minusDays(2), 37.5150, 127.0150);
        long a6 = createPlace(townA, "등가I", CALCULATED_AT.minusDays(2), 37.5160, 127.0160);

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
        // 평점 5.00 동점 셋을 리뷰 수 3·2·1로 세운다 — 페이지 경계(크기 2)가 그 한가운데에 놓인다
        for (int i = 0; i < 2; i++) {
            insertReview(createUser(), a5, 5);
        }
        insertReview(createUser(), a6, 5);

        // 행을 짓는 것은 운영에서 어드민 쓰기 트랜잭션의 몫이다 — 그 경로를 지나치는 이 픽스처는
        // 원본 재구축 문장으로 그 자리를 채운다.
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);

        // 채점 <b>뒤에</b> 만든다 — 행은 있고 점수는 컬럼 기본값 0인 상태가 이 장소의 목적이다
        unscored = createPlace(townA, "등가G", CALCULATED_AT.plusMinutes(5), 37.5300, 127.0300);
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT.plusHours(1));

        // 픽스처를 다 심은 뒤에 스냅샷을 짓는다 — 조회 경로가 보는 것은 이 회차뿐이다
        loader.rebuild();
    }

    /**
     * <b>정렬 여섯 × 두 페이지가 단일 동네에서 같다.</b> 게이트의 본체다.
     *
     * <p>두 페이지를 도는 이유: 커서는 페이지 <b>마지막 행</b>에서 발급되므로 첫 페이지만 보면
     * 커서 왕복이 검증되지 않고, seek 경로(이진 탐색 + 등호 분기)는 두 번째 페이지에서만 돈다.
     */
    @Test
    void 정렬_여섯의_두_페이지가_두_경로에서_같다() {
        for (PlaceSortType sort : PlaceSortType.values()) {
            assertPathsAgree(sort + " 단일 동네", sort, townA, null, 2);
        }
    }

    /**
     * <b>다중 동네도 같다 — 이 게이트가 가장 얇은 얼음이다.</b> DB는 town별 인덱스 range를 filesort로
     * 합쳐 전역 순서를 만들고, 스냅샷은 동네별 배열을 힙으로 merge한다. 두 전순서가 같아야 하는데
     * 그것을 보장하는 것은 <b>타이브레이크까지 포함한 비교자</b> 하나뿐이라, 방향이 하나만 어긋나도
     * 동점 구간에서 갈린다 — 픽스처의 북마크 4건 셋이 두 동네에 걸쳐 있는 이유다.
     */
    @Test
    void 다중_동네_조회도_두_경로에서_같다() {
        for (PlaceSortType sort : PlaceSortType.values()) {
            assertPathsAgree(sort + " 다중 동네", sort, cityTownId, null, 2);
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
    void 태그_필터를_건_요청도_두_경로에서_같다() {
        for (PlaceSortType sort : PlaceSortType.values()) {
            assertPathsAgree(sort + " 태그 필터", sort, townA, SEED_OPTION1_A, 1);
        }
    }

    /**
     * <b>평점 0 구간(리뷰 없음)도 같다.</b> 그 구간은 술어로 끊겨 있던 자리라 (V36 → V37) 규칙이
     * 가장 최근에 뒤집힌 곳이고, 커서가 0점 구간 <em>안으로</em> 진입하는 유일한 경로이기도 하다.
     *
     * <p>등가만 보면 두 경로가 함께 틀려도 그린이므로 표시 계약도 값으로 못 박는다 —
     * 저장은 0이지만 응답의 평점은 여전히 {@code null}이다 ({@code PlacePreviewDto#of}).
     */
    @Test
    void 평점_0_구간도_두_경로에서_같고_응답_평점은_null이다() {
        // 페이지 크기 4 = 평점이 있는 장소 수(a1·a5·a6·a2)라, 2페이지가 통째로 0점 구간이다
        PlaceFilterGetResponse page1 = get(request(townA, PlaceSortType.RATING, null, null, 4));

        String cursor = page1.nextCursor();
        PlaceFilterGetResponse page2 = get(request(townA, PlaceSortType.RATING, null, cursor, 4));

        // 커서로 0점 구간에 실제로 진입한 페이지다
        assertThat(page2.places()).isNotEmpty();
        assertThat(page2.places()).allMatch(p -> p.reviewCount() == 0);
        assertThat(page2.places()).allMatch(p -> p.avgRating() == null);
        assertThat(ids(page2))
                .isEqualTo(dbIds(PlaceSortType.RATING, townA, null,
                        PlaceListCursor.decode(cursor), 4));
    }

    /**
     * <b>채점 전 장소는 두 경로 모두 점수 0의 자리에 선다</b> — 양수 점수 장소들 뒤, 음수 점수
     * 장소(a2) 앞이다. 등가 비교만으로는 <b>두 경로가 함께 이 규칙을 잃은</b> 상태가 그린이 되므로
     * 자리를 값으로 문다.
     *
     * <p>점수의 부호를 픽스처가 아니라 저장된 값에서 확인하는 것이 앞단이다 — a2의 점수가 어쩌다
     * 양수가 되면 "0이 음수 위"라는 주장이 공허해진다.
     */
    @Test
    void 채점_전_장소는_두_경로_모두_인기순_0점_자리에_선다() {
        assertThat(scoreOf(unscored)).as("채점 전 장소의 점수").isZero();
        assertThat(scoreOf(a2)).as("저평점 장소의 점수").isNegative();

        List<Long> ranked = ids(get(request(townA, PlaceSortType.POPULAR, null, null, 10)));

        assertThat(ranked).as("스냅샷 인기순").contains(unscored);
        assertThat(ranked.indexOf(unscored)).as("양수 점수 장소들 뒤").isPositive();
        assertThat(ranked.indexOf(a2)).as("음수 점수 장소 앞")
                .isGreaterThan(ranked.indexOf(unscored));
        assertThat(ranked).as("DB 인기순과 같은 자리")
                .isEqualTo(dbIds(PlaceSortType.POPULAR, townA, null, null, 10));
    }

    /**
     * <b>커서 경계가 0점 구간을 지나도 두 경로가 같다.</b> 0점이 둘(a3·unscored)이라 점수만으로는
     * 경계를 가를 수 없고 id 타이브레이크가 실제로 일한다 — 등호 분기가 빠지면 뒤쪽 0점 장소가
     * 통째로 누락되고, 경계가 느슨하면 앞 페이지의 0점 장소가 다시 실린다.
     *
     * <p>페이지 크기를 <b>0점 구간이 시작되는 자리</b>로 잡아 경계가 반드시 그 구간을 지나게 한다.
     * 숫자를 손으로 적으면 픽스처의 점수가 조금만 흔들려도 경계가 엉뚱한 곳으로 옮겨간다.
     */
    @Test
    void 인기순_커서가_0점_구간을_지나도_두_경로가_같다() {
        List<Long> ranked = ids(get(request(townA, PlaceSortType.POPULAR, null, null, 10)));
        int pageSize = ranked.indexOf(unscored);   // 1페이지 끝이 0점 구간의 첫 장소(a3)다
        assertThat(pageSize).as("0점 구간이 맨 앞이면 경계를 만들 수 없다").isPositive();

        PlaceFilterGetResponse page1 =
                get(request(townA, PlaceSortType.POPULAR, null, null, pageSize));
        assertThat(ids(page1)).isEqualTo(ranked.subList(0, pageSize));
        assertThat(page1.nextCursor()).isNotNull();

        PlaceListCursor cursor = PlaceListCursor.decode(page1.nextCursor());
        assertThat(cursor.key(0)).as("커서 좌표가 0점이다").isZero();

        List<Long> page2 = ids(get(request(townA, PlaceSortType.POPULAR, null,
                page1.nextCursor(), pageSize)));

        assertThat(page2).as("같은 0점 구간에서 이어진다").startsWith(unscored);
        assertThat(page2).as("DB 경로와 같은 페이지")
                .isEqualTo(dbIds(PlaceSortType.POPULAR, townA, null, cursor, pageSize));
        assertThat(ids(page1)).doesNotContainAnyElementsOf(page2);
    }

    /**
     * <b>좌표 없는 장소는 두 경로 모두 거리순에서만 빠진다.</b> 거리를 잴 수 없는 장소를 "무한대"로
     * 뒤에 붙이면 커서 seek이 그 행을 페이지 경계에서 조용히 흘리므로, 두 경로 다 후보 단계에서
     * 끊는다는 것이 계약이다.
     */
    @Test
    void 좌표_없는_장소는_두_경로_모두_거리순에서만_빠진다() {
        assertThat(ids(get(request(townA, PlaceSortType.DISTANCE, null, null, 10))))
                .as("스냅샷 거리순").doesNotContain(a3);
        assertThat(dbIds(PlaceSortType.DISTANCE, townA, null, null, 10))
                .as("DB 거리순").doesNotContain(a3);

        assertThat(ids(get(request(townA, PlaceSortType.LATEST, null, null, 10))))
                .as("스냅샷 최신순").contains(a3);
    }

    // === helpers ===

    /**
     * 두 경로의 1·2 페이지가 <b>id 순서·hasNext</b>까지 같은지 확인한다.
     *
     * <p><b>비어 있지 않음을 함께 단언하는 것이 핵심이다.</b> 빈 응답 둘은 언제나 같으므로, 그 확인이
     * 없으면 픽스처가 조용히 무너졌을 때 게이트가 공허하게 그린이 된다.
     *
     * <p>2페이지의 대조군에는 <b>서비스가 발급한 커서</b>를 그대로 푼 값을 넣는다 — 커서를 따로
     * 지어내면 왕복(발급 → 해석 → 재개)이 검증에서 빠지고, 두 경로가 서로 다른 좌표에서 재개돼도
     * 그린이 될 수 있다.
     */
    private void assertPathsAgree(String label, PlaceSortType sort, long town, Long option1TagId,
            int pageSize) {

        PlaceFilterGetResponse page1 = get(request(town, sort, option1TagId, null, pageSize));

        assertThat(ids(page1)).as("%s - 1페이지가 비면 게이트가 공허하다", label).isNotEmpty();
        assertThat(page1.nextCursor()).as("%s - 커서가 없으면 seek 경로를 못 본다", label).isNotNull();
        assertThat(ids(page1)).as("%s - 1페이지 id 순서", label)
                .isEqualTo(dbIds(sort, town, option1TagId, null, pageSize));

        PlaceListCursor cursor = PlaceListCursor.decode(page1.nextCursor());
        assertThat(cursor.sort()).as("%s - 커서의 정렬 축", label).isEqualTo(sort);

        PlaceFilterGetResponse page2 =
                get(request(town, sort, option1TagId, page1.nextCursor(), pageSize));

        assertThat(ids(page2)).as("%s - 2페이지가 비면 seek을 못 본다", label).isNotEmpty();
        assertThat(ids(page2)).as("%s - 2페이지 id 순서", label)
                .isEqualTo(dbIds(sort, town, option1TagId, cursor, pageSize));
        assertThat(page2.nextCursor() != null).as("%s - 2페이지 hasNext", label)
                .isEqualTo(dbHasNext(sort, town, option1TagId, cursor, pageSize));
        // 두 페이지가 겹치지 않는다 — 등가만 보면 두 경로가 함께 중복을 내도 그린이다
        assertThat(ids(page1)).as("%s - 페이지 중복", label)
                .doesNotContainAnyElementsOf(ids(page2));
    }

    /** 대조군 한 페이지 — 리포지토리에 같은 입력을 넣어 얻은 id 목록 */
    private List<Long> dbIds(PlaceSortType sort, long town, Long option1TagId,
            PlaceListCursor cursor, int pageSize) {
        List<Long> rows = dbRowIds(sort, town, option1TagId, cursor, pageSize);
        return rows.size() > pageSize ? rows.subList(0, pageSize) : rows;
    }

    /** 대조군의 hasNext — 서비스와 같은 규칙(페이지 크기 + 1을 떠서 넘치면 참)이다 */
    private boolean dbHasNext(PlaceSortType sort, long town, Long option1TagId,
            PlaceListCursor cursor, int pageSize) {
        return dbRowIds(sort, town, option1TagId, cursor, pageSize).size() > pageSize;
    }

    /**
     * 대조군의 원본 호출. <b>정렬별로 커서 튜플을 푸는 자리가 곧 서비스가 싣는 자리와 짝</b>이라,
     * 여기서 칸을 잘못 꺼내면 두 경로가 다른 좌표에서 재개돼 게이트가 그것을 잡아낸다.
     *
     * <p>거리순만 리포지토리가 순서를 만들지 않는다 — 후보만 받아 {@code DistanceSort}로 자르는
     * 것이 DB 경로의 모양이었고, 그 형상을 그대로 재현해야 대조가 성립한다.
     */
    private List<Long> dbRowIds(PlaceSortType sort, long town, Long option1TagId,
            PlaceListCursor cursor, int pageSize) {

        List<Long> townIds = townHierarchyResolver.resolveLeafTownIdsOrThrow(town);
        Long mainTagId = option1TagId == null ? null : SEED_MAIN_TAG;
        List<Long> subA = option1TagId == null ? null : List.of(option1TagId);
        Long cursorPlaceId = cursor == null ? null : cursor.placeId();
        int limit = pageSize + 1;

        return transactionTemplate.execute(status -> switch (sort) {
            case POPULAR -> dbQueryRepository.findPopularRows(
                            townIds, mainTagId, subA, null,
                            cursor == null ? null : cursor.key(0), cursorPlaceId, limit)
                    .stream().map(PopularRow::placeId).toList();
            case LATEST -> dbQueryRepository.findLatestRows(
                            townIds, mainTagId, subA, null,
                            cursor == null ? null : (long) cursor.key(0), cursorPlaceId, limit)
                    .stream().map(LatestRow::placeId).toList();
            case RATING -> dbQueryRepository.findRatingRows(
                            townIds, mainTagId, subA, null,
                            cursor == null ? null : cursor.key(0),
                            cursor == null ? null : (long) cursor.key(1), cursorPlaceId, limit)
                    .stream().map(RatingRow::placeId).toList();
            case REVIEW_COUNT -> dbQueryRepository.findReviewCountRows(
                            townIds, mainTagId, subA, null,
                            cursor == null ? null : (long) cursor.key(0), cursorPlaceId, limit)
                    .stream().map(CountRow::placeId).toList();
            case BOOKMARK_COUNT -> dbQueryRepository.findBookmarkCountRows(
                            townIds, mainTagId, subA, null,
                            cursor == null ? null : (long) cursor.key(0), cursorPlaceId, limit)
                    .stream().map(CountRow::placeId).toList();
            case DISTANCE -> distanceRowIds(townIds, mainTagId, subA, cursor, limit);
        });
    }

    /** 거리순 대조군 — 후보 전량을 받아 같은 기준 좌표·같은 커서로 자른다 */
    private List<Long> distanceRowIds(List<Long> townIds, Long mainTagId, List<Long> subA,
            PlaceListCursor cursor, int limit) {

        List<DistanceCandidateRow> candidates =
                dbQueryRepository.findDistanceCandidates(townIds, mainTagId, subA, null);
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 기준 좌표는 커서에 박제된 것이 항상 이긴다 — 서비스와 같은 규칙이라야 대조가 성립한다
        double refLat = cursor == null ? REF_LAT : cursor.key(0);
        double refLng = cursor == null ? REF_LNG : cursor.key(1);

        return DistanceSort.topK(
                        candidates.stream()
                                .map(c -> new DistanceSort.Candidate(
                                        c.placeId(), c.latitude(), c.longitude()))
                                .toList(),
                        refLat, refLng,
                        cursor == null ? null : cursor.key(2),
                        cursor == null ? null : cursor.placeId(),
                        Math.min(limit, candidates.size()))
                .stream().map(DistanceSort.Ranked::placeId).toList();
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
                distance ? REF_LAT : null, distance ? REF_LNG : null);
    }

    private List<Long> ids(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeId).toList();
    }

    /** 저장된 인기 점수. 부호를 주장하는 단언이 픽스처 계산에 기대지 않게 하는 자리다 */
    private double scoreOf(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT popular_score FROM place_stats WHERE place_id = ?", Double.class, placeId);
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
                VALUES (?, '등가IT', ?, true, ?, ?, ?)""",
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
                VALUES (?, ?, ?, 'EVENING', '등가 검증용 리뷰 본문입니다.', ?, ?, ?)""",
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
