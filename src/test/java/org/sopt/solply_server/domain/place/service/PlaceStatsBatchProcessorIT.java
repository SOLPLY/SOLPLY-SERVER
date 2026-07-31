package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, PlaceStatsBatchProcessor.class, PlaceStatsProperties.class})
class PlaceStatsBatchProcessorIT extends MySqlContainerSupport {

    private static final double BOOKMARK_WEIGHT = 1.0;
    private static final double REVIEW_WEIGHT = 3.0;
    private static final double HALF_LIFE_DAYS = 90.0;

    /** 배치 기준 시각. 모든 픽스처의 created_at을 이 시각 기준 상대값으로 넣는다. */
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    PlaceStatsRepository placeStatsRepository;

    @Autowired
    PlaceStatsBatchProcessor batchProcessor;

    @Autowired
    PlaceStatsProperties properties;

    @Autowired
    EntityManager em;

    /**
     * 프로세서가 <em>스스로 연</em> 트랜잭션 이름의 접두사. 스프링은 트랜잭션 이름을
     * {@code FQCN.메서드명}으로 짓는다({@code TransactionAspectSupport}).
     *
     * <p><b>메서드명까지 박지 않고 접두사로 두는 이유.</b> 예전에는 {@code ".recalculateAll"}까지
     * 하드코딩했는데, 그러면 나중에 추가되는 트랜잭션 진입점이 이 프로브에서 <b>구조적으로 배제</b>돼
     * 격리 계약이 검증되지 않은 채로 들어온다. 실제로 {@code recalculateIfEmpty}가 그 구멍으로
     * 들어왔고, 거기서 {@code isolation}·{@code @Transactional}을 통째로 지우는 변이 2건이
     * 전부 살아남았다(실측). 접두사로 넓히면 이 클래스가 여는 모든 트랜잭션이 프로브에 걸린다.
     */
    private static final String BATCH_TX_NAME_PREFIX =
            PlaceStatsBatchProcessor.class.getName() + ".";

    /** 트랜잭션 <em>안쪽</em>에서 관측한 {@code @@transaction_isolation}. @BeforeEach에서 비운다. */
    private static final List<String> OBSERVED_ISOLATIONS = new CopyOnWriteArrayList<>();

    /**
     * 배치 트랜잭션이 열린 직후의 격리 수준을 서버에 물어 기록한다.
     *
     * <p>스프링이 커넥션에 건 격리는 트랜잭션 종료 시 원복되므로 밖에서는 볼 수 없다.
     * Spring Framework 6.1의 {@code TransactionExecutionListener}만이 "이미 begin됐고 아직
     * 아무 일도 안 한" 시점에 끼어들 수 있어, 트랜잭션 매니저에 직접 붙인다
     * (리스너 빈을 자동으로 주워 가지는 않는다).
     *
     * <p><b>이름을 {@link #BATCH_TX_NAME_PREFIX}로 좁히는 이유.</b> 좁히지 않으면 이 컨텍스트의
     * <em>모든</em> 트랜잭션(테스트 메서드마다 하나씩)마다 SELECT가 날아가고 static 리스트에
     * 쌓인다. 좁히면 기록 대상이 "프로세서가 스스로 연 트랜잭션" 하나로 줄어든다 — 다른
     * 테스트들은 이미 열린 테스트 트랜잭션에 프로세서가 <em>참여</em>하므로 begin 자체가
     * 일어나지 않아 애초에 이 리스너를 타지 않는다.
     *
     * <p><b>실측 단서 — 이 필터는 테스트로 검출되지 않는다.</b> 필터를 제거해도 순차 실행에서는
     * 14건이 전부 그린이었다. {@code @BeforeEach}의 {@code clear()}가 스프링의 테스트 트랜잭션
     * begin <em>이후에</em> 돌아(스프링은 {@code beforeTestMethod}에서 트랜잭션을 열고 그 다음
     * {@code @BeforeEach}를 부른다) 그 기록을 지워 버리기 때문이다. 그러므로 이 필터는 변이로
     * 잡히는 장치가 아니라 {@code junit.jupiter.execution.parallel.enabled}를 켰을 때
     * 무관한 트랜잭션이 섞여 드는 플레이크를 막는 <b>예방책</b>이다.
     * 반면 {@code BATCH_TX_NAME_PREFIX} 상수 자체는 검증된다 — 틀리면 격리 테스트가 빈 리스트로 실패한다.
     */
    @TestConfiguration
    static class IsolationProbeConfig {

        IsolationProbeConfig(PlatformTransactionManager txManager, EntityManagerFactory emf) {
            ((AbstractPlatformTransactionManager) txManager).addListener(
                    new TransactionExecutionListener() {
                        @Override
                        public void afterBegin(TransactionExecution tx, Throwable beginFailure) {
                            String name = tx.getTransactionName();
                            if (name == null || !name.startsWith(BATCH_TX_NAME_PREFIX)) {
                                return;
                            }
                            EntityManager bound =
                                    EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
                            OBSERVED_ISOLATIONS.add(bound == null ? "NO-EM" : String.valueOf(
                                    bound.createNativeQuery("SELECT @@transaction_isolation")
                                            .getSingleResult()));
                        }
                    });
        }
    }

    private int userSeq;
    private long placeA;
    private long placeB;
    private long placeC;

    @BeforeEach
    void setUp() {
        OBSERVED_ISOLATIONS.clear();
        List<?> placeIds = em.createNativeQuery(
                "SELECT p.id FROM places p WHERE p.active = true ORDER BY p.id LIMIT 3")
                .getResultList();
        placeA = ((Number) placeIds.get(0)).longValue();
        placeB = ((Number) placeIds.get(1)).longValue();
        placeC = ((Number) placeIds.get(2)).longValue();
    }

    /**
     * 픽스처용 유저 1명. users는 NOT NULL이면서 DEFAULT가 없는 컬럼이 role 하나뿐이고
     * created_at/updated_at 컬럼이 아예 없다 (V1__init_tables.sql 기준).
     * nickname이 UNIQUE라 순번을 붙여 조회 키로 쓴다.
     */
    private long createUser() {
        String nickname = "배치테스트유저" + (++userSeq);
        em.createNativeQuery("INSERT INTO users (role, nickname) VALUES ('USER', :nickname)")
                .setParameter("nickname", nickname)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE nickname = :nickname")
                .setParameter("nickname", nickname)
                .getSingleResult()).longValue();
    }

    /**
     * 북마크 1건을 "기준 시각으로부터 며칠 전"에 만든다.
     * bookmarks에 uk_bookmark_user_target (user_id, target_type, target_id) 유니크 제약이 있어
     * 같은 장소에 2건을 넣으려면 유저가 달라야 한다 — 호출마다 새 유저를 만든다.
     */
    private void insertBookmark(long placeId, int daysAgo) {
        em.createNativeQuery("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (:userId, 'PLACE', :placeId, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("createdAt", CALCULATED_AT.minusDays(daysAgo))
                .executeUpdate();
    }

    /**
     * 북마크 1건을 <b>기준 시각 이후</b>에 만든다 — 배치가 커밋된 뒤 사용자가 누른 상황의 재현이다.
     * {@link #insertBookmark}에 음수를 넘겨도 같은 행이 만들어지지만, 이 시나리오는 "며칠 전"이라는
     * 축과 성질이 달라(감쇠가 아니라 집계 대상 여부의 문제) 별도 이름을 준다.
     */
    private void insertBookmarkAfterCalculatedAt(long placeId, int minutesAfter) {
        em.createNativeQuery("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (:userId, 'PLACE', :placeId, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("createdAt", CALCULATED_AT.plusMinutes(minutesAfter))
                .executeUpdate();
    }

    /** 위의 리뷰판. 리뷰 축에도 같은 상한이 걸려 있는지 보기 위한 것이다. */
    private void insertReviewAfterCalculatedAt(long placeId, int rating, int minutesAfter) {
        em.createNativeQuery("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (:userId, :placeId, :visitedAt, 'EVENING',
                        '배치 검증용 리뷰 본문입니다.', :rating, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("visitedAt", CALCULATED_AT.toLocalDate())
                .setParameter("rating", rating)
                .setParameter("createdAt", CALCULATED_AT.plusMinutes(minutesAfter))
                .executeUpdate();
    }

    /**
     * 코스 북마크 1건. bookmarks.target_id는 PLACE와 COURSE가 숫자 공간을 공유하므로
     * placeId와 같은 값을 target_id로 넣어도 유효한 행이 된다 — 집계가 target_type으로
     * 걸러내지 않으면 코스 북마크가 같은 id의 장소 점수를 부풀린다.
     */
    private void insertCourseBookmark(long targetId, int daysAgo) {
        em.createNativeQuery("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (:userId, 'COURSE', :targetId, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("targetId", targetId)
                .setParameter("createdAt", CALCULATED_AT.minusDays(daysAgo))
                .executeUpdate();
    }

    /** 리뷰에는 (user, place) 유니크 제약이 없지만 북마크와 대칭을 맞춰 매번 새 유저를 쓴다 */
    private void insertReview(long placeId, int rating, int daysAgo) {
        em.createNativeQuery("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (:userId, :placeId, :visitedAt, 'EVENING',
                        '배치 검증용 리뷰 본문입니다.', :rating, :createdAt, :createdAt)
                """)
                .setParameter("userId", createUser())
                .setParameter("placeId", placeId)
                .setParameter("visitedAt", CALCULATED_AT.minusDays(daysAgo).toLocalDate())
                .setParameter("rating", rating)
                .setParameter("createdAt", CALCULATED_AT.minusDays(daysAgo))
                .executeUpdate();
    }

    /**
     * 리포지토리를 직접 부르지 않고 프로세서를 거친다 — 설정 주입 경로(properties → SQL 파라미터)까지
     * 함께 검증하기 위해서다. 가중치·반감기를 인자로 받지 않는 것은 프로세서가 그 값을
     * {@link PlaceStatsProperties}에서 가져오기 때문이고, 기본값이 아래 상수와 같다는 것은
     * {@code 기본_설정값은_설계에서_정한_가중치와_반감기다}가 못 박는다.
     */
    private int runBatch() {
        return batchProcessor.recalculateAll(CALCULATED_AT);
    }

    /**
     * 반감기만 다르게 주고 싶을 때 쓴다. 프로세서에는 반감기 파라미터가 없으므로 설정 빈을 잠시
     * 바꿔 넣고 되돌린다 — 이렇게 해야 "설정값이 실제로 SQL까지 흘러가는가"를 프로세서 경유로 본다.
     * 컨텍스트가 캐시돼 빈이 공유되므로 finally 복구가 필수다.
     *
     * <p>영속성 컨텍스트 정리는 @Modifying(clearAutomatically = true)가 이미 해준다.
     */
    private int runBatch(double halfLifeDays) {
        double original = properties.getHalfLifeDays();
        properties.setHalfLifeDays(halfLifeDays);
        try {
            return batchProcessor.recalculateAll(CALCULATED_AT);
        } finally {
            properties.setHalfLifeDays(original);
        }
    }

    private PlaceStats statsOf(long placeId) {
        return placeStatsRepository.findById(placeId).orElseThrow();
    }

    @Test
    void 오늘_생긴_북마크는_감쇠_없이_가중치_그대로_반영된다() {
        insertBookmark(placeA, 0);

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(1.0, within(0.000001));
        assertThat(statsOf(placeA).getBookmarkCount()).isEqualTo(1);
    }

    @Test
    void 반감기_90일_전_북마크는_절반만_반영된다() {
        insertBookmark(placeA, 90);

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.5, within(0.000001));
    }

    @Test
    void 평점은_3점을_중심으로_가감된다() {
        insertReview(placeA, 5, 0);   // +3.0 * (5-3) * 1.0 = +6.0
        insertReview(placeB, 3, 0);   //  3.0 * (3-3)       =  0.0
        insertReview(placeC, 1, 0);   //  3.0 * (1-3) * 1.0 = -6.0

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue()).isCloseTo(6.0, within(0.000001));
        assertThat(statsOf(placeB).getPopularScore().doubleValue()).isCloseTo(0.0, within(0.000001));
        assertThat(statsOf(placeC).getPopularScore().doubleValue()).isCloseTo(-6.0, within(0.000001));
    }

    @Test
    void 북마크_점수와_리뷰_점수는_합산된다() {
        insertBookmark(placeA, 0);    // +1.0
        insertBookmark(placeA, 90);   // +0.5
        insertReview(placeA, 4, 90);  // +3.0 * (4-3) * 0.5 = +1.5

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(3.0, within(0.000001));
        assertThat(stats.getBookmarkCount()).isEqualTo(2);
        assertThat(stats.getReviewCount()).isEqualTo(1);
        assertThat(stats.getAvgRating().doubleValue()).isCloseTo(4.0, within(0.005));
    }

    @Test
    void 활동이_없는_장소는_0점_행으로_기록된다() {
        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(0.0, within(0.000001));
        assertThat(stats.getBookmarkCount()).isZero();
        assertThat(stats.getReviewCount()).isZero();
        assertThat(stats.getAvgRating()).isNull();
    }

    @Test
    void 같은_기준시각으로_두_번_실행하면_결과가_같다() {
        insertBookmark(placeA, 0);
        insertBookmark(placeA, 37);
        insertReview(placeB, 2, 12);

        runBatch();
        String firstRun = snapshotOfAllStats();

        runBatch();
        String secondRun = snapshotOfAllStats();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    /**
     * <b>위 테스트만으로는 멱등성이 검증되지 않는다.</b> 두 실행 사이에 소스가 그대로면 상한이
     * 없어도 결과가 같아, "같은 {@code calculatedAt}이면 결과가 같다"는 주장의 유효 범위가
     * "그 사이 아무도 북마크를 누르지 않았다면"으로 조용히 좁혀진다. 실제 배치는 6초대 동안 돌고
     * 재실행은 며칠 뒤일 수도 있으므로 그 전제는 성립하지 않는다.
     *
     * <p>그래서 두 실행 <em>사이에</em> 기준 시각 이후의 활동을 넣고도 결과가 같은지 본다.
     * 상한 조건({@code created_at <= :calculatedAt})을 지우면 여기서만 깨진다.
     */
    @Test
    void 두_실행_사이에_기준시각_이후_활동이_들어와도_결과가_같다() {
        insertBookmark(placeA, 0);
        insertBookmark(placeA, 37);
        insertReview(placeB, 2, 12);

        runBatch();
        String firstRun = snapshotOfAllStats();

        insertBookmarkAfterCalculatedAt(placeA, 30);
        insertReviewAfterCalculatedAt(placeB, 5, 30);

        runBatch();
        String secondRun = snapshotOfAllStats();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    /**
     * 배치 기준 시각 이후에 생긴 북마크는 이번 세대의 집계 대상이 아니다.
     *
     * <p>이 상한이 없으면 두 가지가 동시에 틀어진다.
     * <ul>
     *   <li>{@code bookmark_count}에 이미 포함돼, {@code PlaceDisplayCount.correct}가
     *       {@code myBookmarkedAt > calculatedAt}으로 더하는 +1과 겹쳐 <b>같은 1건이 두 번</b> 반영된다.</li>
     *   <li>{@code TIMESTAMPDIFF}가 음수가 되어 {@code POW(0.5, 음수) > 1} — 감쇠가 아니라 증폭이다.
     *       상한이 없을 때 아래 시나리오는 {@code 1.0}이 아니라 {@code 2.0002...}가 나온다.</li>
     * </ul>
     */
    @Test
    void 기준시각_이후에_생긴_북마크는_집계에_들어가지_않는다() {
        insertBookmark(placeA, 0);                        // 기준 시각 정각 → +1.0, 카운트 1
        insertBookmarkAfterCalculatedAt(placeA, 30);      // 30분 뒤 → 무시돼야 한다

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
        // 증폭까지 잡으려면 점수를 정확히 봐야 한다 — 카운트만 보면 POW 쪽 회귀를 놓친다
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(1.0, within(0.000001));
    }

    /** 리뷰 축에도 같은 상한이 걸려 있어야 한다 — 점수·건수·평균 평점 셋 모두 영향을 받는다. */
    @Test
    void 기준시각_이후에_생긴_리뷰는_집계에_들어가지_않는다() {
        insertReview(placeA, 5, 0);                       // +3.0 * (5-3) * 1.0 = +6.0
        insertReviewAfterCalculatedAt(placeA, 1, 30);     // 무시돼야 한다 (반영되면 −6점대로 끌려간다)

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getReviewCount()).isEqualTo(1);
        assertThat(stats.getAvgRating().doubleValue()).isCloseTo(5.0, within(0.005));
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(6.0, within(0.000001));
    }

    /**
     * 상한이 {@code <}가 아니라 {@code <=}인 것을 못 박는다. {@code calculatedAt}과 정확히 같은
     * 시각의 활동은 포함돼야 한다 — {@code PlaceDisplayCount.correct}가 {@code isAfter}(엄격 초과)로
     * 판정하므로, 여기가 {@code <}가 되면 경계값 1건이 <b>양쪽 어디에도 세어지지 않아</b> 사라진다.
     */
    @Test
    void 기준시각과_정확히_같은_시각의_북마크는_포함된다() {
        insertBookmark(placeA, 0);   // created_at == CALCULATED_AT

        runBatch();

        assertThat(statsOf(placeA).getBookmarkCount()).isEqualTo(1);
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(1.0, within(0.000001));
    }

    /**
     * 전량 재계산의 핵심 주장은 "이전 값이 무엇이든 원본 기준으로 덮어쓴다"이다.
     * 나머지 테스트는 전부 place_stats가 빈 상태에서 시작해 INSERT 경로만 타고,
     * 멱등성 테스트는 입력이 같아 ON DUPLICATE KEY UPDATE에서 컬럼 하나가 통째로 빠져도 통과한다.
     * 이 테스트만이 UPDATE 분기에서 값이 실제로 새 값으로 바뀌는지 본다.
     */
    @Test
    void 재실행하면_이전_값이_새_값으로_덮어써진다() {
        insertBookmark(placeA, 0);
        runBatch();

        PlaceStats before = statsOf(placeA);
        assertThat(before.getPopularScore().doubleValue()).isCloseTo(1.0, within(0.000001));
        assertThat(before.getBookmarkCount()).isEqualTo(1);
        assertThat(before.getReviewCount()).isZero();
        assertThat(before.getAvgRating()).isNull();

        // 1회차 이후 원본이 늘었다 — 북마크 +1, 리뷰 +1(5점, 감쇠 없음 → +6.0)
        insertBookmark(placeA, 0);
        insertReview(placeA, 5, 0);

        runBatch();

        PlaceStats after = statsOf(placeA);
        assertThat(after.getPopularScore().doubleValue()).isCloseTo(8.0, within(0.000001));
        assertThat(after.getBookmarkCount()).isEqualTo(2);
        assertThat(after.getReviewCount()).isEqualTo(1);
        assertThat(after.getAvgRating().doubleValue()).isCloseTo(5.0, within(0.005));
    }

    @Test
    void 코스_북마크는_같은_id의_장소_점수에_섞이지_않는다() {
        insertBookmark(placeA, 0);            // PLACE 북마크 → +1.0
        insertCourseBookmark(placeA, 0);      // 같은 target_id의 COURSE 북마크 → 무시돼야 한다

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(1.0, within(0.000001));
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
    }

    /**
     * town_id·active는 places에서 비정규화해 오는 값이고, 낡으면 순위가 아니라 노출 대상 자체가
     * 틀린다(V24·PlaceStats 주석 참고). Flyway 시드에 비활성 장소가 없어 이 테스트가 없으면
     * active = false 경로가 한 번도 실행되지 않는다.
     */
    @Test
    void 장소의_town_id와_active를_그대로_복사한다() {
        em.createNativeQuery("UPDATE places SET active = false WHERE id = :id")
                .setParameter("id", placeA)
                .executeUpdate();
        long expectedTownId = ((Number) em.createNativeQuery(
                "SELECT town_id FROM places WHERE id = :id")
                .setParameter("id", placeA)
                .getSingleResult()).longValue();

        runBatch();

        PlaceStats deactivated = statsOf(placeA);
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getTownId()).isEqualTo(expectedTownId);
        // 손대지 않은 장소는 active가 그대로 true여야 한다 (전부 false로 미는 실수 방지)
        assertThat(statsOf(placeB).isActive()).isTrue();
    }

    /**
     * 다른 테스트가 전부 반감기 90일 하나만 써서, SQL에 90이 하드코딩돼 있어도 전부 통과한다.
     * 설정값이 실제로 쿼리까지 전달되는지 보려면 다른 반감기가 하나는 있어야 한다.
     */
    @Test
    void 반감기_설정값이_실제_계산에_반영된다() {
        insertBookmark(placeA, 45);

        runBatch(45.0);   // 반감기를 45일로 주면 45일 전 활동이 정확히 절반이 된다

        // 반감기 90일이었다면 0.5^(45/90) = 0.7071이 나온다
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.5, within(0.000001));
    }

    @Test
    void 기본_설정값은_설계에서_정한_가중치와_반감기다() {
        assertThat(properties.getBookmarkWeight()).isEqualTo(BOOKMARK_WEIGHT);
        assertThat(properties.getReviewWeight()).isEqualTo(REVIEW_WEIGHT);
        assertThat(properties.getHalfLifeDays()).isEqualTo(HALF_LIFE_DAYS);
    }

    /**
     * 배치 트랜잭션이 실제로 READ COMMITTED로 열리는지 서버에 직접 물어 확인한다.
     *
     * <p><b>이 테스트만 {@code NOT_SUPPORTED}인 이유 — 반드시 읽을 것.</b> {@code @DataJpaTest}의
     * 테스트 메서드는 이미 트랜잭션 안에서 돌고, 프로세서의 {@code @Transactional(REQUIRED)}은
     * 그 트랜잭션에 <em>참여</em>한다. 스프링은 참여 시 격리 수준 지정을 조용히 무시하므로
     * ({@code validateExistingTransaction} 기본 false) 나머지 테스트에서 프로세서를 불러 격리를
     * 재보면 전부 {@code REPEATABLE-READ}가 나온다 — 실측으로 확인했다. 즉 <b>일반 슬라이스
     * 테스트로는 이 계약을 절대 검증할 수 없다.</b> 바깥 트랜잭션을 걷어내야만 프로세서가 자기
     * 트랜잭션을 열고, 그때 비로소 {@code READ-COMMITTED}가 관측된다 (운영의 스케줄러 경로와 동일).
     *
     * <p>격리 수준은 트랜잭션이 끝나면 커넥션에서 원복되므로 사후 관측이 불가능하다.
     * 그래서 {@link IsolationProbeConfig}가 {@code TransactionExecutionListener.afterBegin}에서
     * 트랜잭션 안쪽 값을 잡아 둔다.
     *
     * <p><b>커밋이 불가피한 이유:</b> 격리 수준은 트랜잭션이 실제로 열려야만 관측되고, 그 트랜잭션을
     * 여는 주체가 프로세서 자신이어야 한다(테스트가 열면 검증 대상이 바뀐다). 프로세서는 커밋 여부를
     * 호출자에게 위임하지 않으므로 롤백시킬 지점이 없다. 그래서 이 테스트만 배치 결과를
     * <b>실제로 커밋한다</b> — 뒷정리는 {@link #cleanUpCommittedStats()}가 맡는다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 배치_트랜잭션은_READ_COMMITTED로_열린다() {
        batchProcessor.recalculateAll(CALCULATED_AT);

        assertThat(OBSERVED_ISOLATIONS).containsExactly("READ-COMMITTED");
    }

    /**
     * 최초 적재 진입점도 같은 계약을 진다. <b>이 커밋의 설계 논거 전체가 이 어노테이션 하나에
     * 걸려 있다</b> — Flyway 백필을 기각한 이유가 "RR에서 {@code bookmarks} 1,073만 행에
     * next-key 락"이었으므로, 여기서 RC가 사라지면 부팅 시 정확히 그 장애 모드가 재현된다.
     * {@code @Transactional} 자체가 사라지면 백필이 조용히 한 번도 돌지 않는 상태가 된다.
     *
     * <p>격리는 {@code afterBegin} 시점 — 즉 <b>가드 실행 이전</b>에 관측되므로, place_stats가
     * 비어 있든 이미 채워져 있든 이 단언은 동일하게 성립한다.
     *
     * <p>{@code NOT_SUPPORTED}인 이유와 커밋이 불가피한 이유는 위 테스트와 같다. 이 테스트도
     * 테이블이 비어 있으면 결과를 실제로 커밋하므로 뒷정리는 {@link #cleanUpCommittedStats()}가 맡는다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 최초_적재_트랜잭션도_READ_COMMITTED로_열린다() {
        batchProcessor.recalculateIfEmpty(CALCULATED_AT);

        assertThat(OBSERVED_ISOLATIONS).containsExactly("READ-COMMITTED");
    }

    /**
     * 위 테스트의 짝. <b>이미 트랜잭션이 열려 있으면 프로세서는 새 트랜잭션을 열지 않고
     * 참여하며, 그때 격리 지정은 조용히 버려진다</b>는 사실을 못 박는다
     * ({@code validateExistingTransaction} 기본 false).
     *
     * <p>{@code PlaceStatsBatchProcessor.recalculateAll} 이름의 트랜잭션이 한 번도 begin되지
     * 않았다는 것이 곧 "참여했다"의 증거다. 프로세서를 {@code REQUIRES_NEW}로 바꾸면 이 단언이
     * 깨진다 — 즉 전파 방식 변경을 눈치채는 장치다(실측 확인).
     *
     * <p>이것이 나머지 12개 테스트가 격리 수준을 검증할 수 없는 이유이자,
     * {@code PlaceStatsFacade}에 {@code @Transactional}을 붙이면 안 되는 이유다.
     */
    @Test
    void 이미_열린_트랜잭션에_참여하면_새_트랜잭션을_열지_않는다() {
        runBatch();

        assertThat(OBSERVED_ISOLATIONS).isEmpty();
    }

    /**
     * {@code 배치_트랜잭션은_READ_COMMITTED로_열린다}가 커밋한 place_stats 행을 지운다.
     *
     * <p>지우지 않으면 같은 싱글턴 컨테이너를 쓰는 {@code PlaceStatsRepositoryIT}의
     * <b>2건이 모두</b> 깨진다 — {@code 통계가_없는_장소는_빈_결과를_반환한다}는 빈 결과를 기대하고,
     * {@code 네이티브로_삽입한_행을_엔티티로_읽을_수_있다}는 같은 place_id INSERT가 중복 키로 터진다.
     * (실측: 클래스 실행 순서를 뒤집고 이 정리를 빼면 정확히 그 2건이 FAILED.)
     *
     * <p><b>{@code @AfterEach}가 아니라 {@code @AfterAll}인 이유.</b> 스프링의
     * {@code TransactionalTestExecutionListener}는 테스트 트랜잭션을 {@code @AfterEach}
     * <em>이후에</em> 롤백한다. 즉 {@code @AfterEach} 시점에는 방금 배치가 INSERT한 place_stats 행이
     * 아직 커밋 안 된 채 X 락에 잡혀 있고, 여기처럼 <b>별도 커넥션</b>으로 DELETE를 날리면
     * 그 락을 기다리다 {@code ERROR 1205 Lock wait timeout}(기본 50초)으로 죽는다.
     * 클래스의 마지막 트랜잭션까지 끝난 뒤 도는 {@code @AfterAll}이라야 안전하다.
     *
     * <p>테스트 본문의 {@code finally}가 아닌 것은 예외·실패 경로까지 확실히 덮기 위해서다.
     */
    @AfterAll
    static void cleanUpCommittedStats() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
        }
    }

    /**
     * place_stats를 비운다. 같은 클래스의 {@code 배치_트랜잭션은_READ_COMMITTED로_열린다}가 결과를
     * <b>실제로 커밋</b>하고 정리는 {@link #cleanUpCommittedStats()}(@AfterAll)에서야 돌기 때문에,
     * 그 테스트가 먼저 실행되면 뒤따르는 테스트에게 place_stats가 비어 보이지 않는다.
     * 아래 최초 적재 테스트들은 "비었는가"가 곧 검증 대상이라 시작 상태를 직접 못 박아야 한다.
     *
     * <p>이 DELETE는 테스트 트랜잭션과 함께 롤백되므로 커밋된 행을 영구히 지우지 않는다.
     */
    private void clearStats() {
        em.createNativeQuery("DELETE FROM place_stats").executeUpdate();
    }

    @Test
    void 비어_있으면_최초_적재가_모든_장소를_채운다() {
        clearStats();
        insertBookmark(placeA, 0);
        long placeCount = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM places")
                .getSingleResult()).longValue();

        OptionalInt affected = batchProcessor.recalculateIfEmpty(CALCULATED_AT);

        // 빈 테이블이라 전부 INSERT 경로 → MySQL이 INSERT를 1로 세므로 영향 행 수 = 장소 수.
        // "재계산했고 영향 행 0"과 "건너뜀"이 다른 사실이라는 것을 값으로도 못 박는다.
        assertThat(affected).hasValue((int) placeCount);
        assertThat(placeStatsRepository.count()).isEqualTo(placeCount);
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(1.0, within(0.000001));
    }

    /**
     * 가드가 실제로 재계산을 막는지 본다. 센티널 행을 하나 심어 두고, 최초 적재가 그것을
     * 덮어쓰지 않는지·다른 장소 행을 만들지 않는지 둘 다 확인한다.
     * 가드를 제거하면 UPSERT가 전 장소를 채우고 센티널 점수를 실제 집계값으로 덮어써 둘 다 깨진다.
     */
    @Test
    void 이미_채워져_있으면_최초_적재는_다시_돌지_않는다() {
        clearStats();
        em.createNativeQuery("""
                INSERT INTO place_stats
                    (place_id, town_id, active, popular_score, bookmark_count,
                     review_count, avg_rating, calculated_at)
                SELECT p.id, p.town_id, p.active, 777.000000, 0, 0, NULL, :calculatedAt
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("placeId", placeA)
                .setParameter("calculatedAt", CALCULATED_AT)
                .executeUpdate();
        insertBookmark(placeA, 0);   // 재계산이 돌면 점수가 777이 아니라 1.0이 된다

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger processorLogger = (Logger) LoggerFactory.getLogger(PlaceStatsBatchProcessor.class);
        processorLogger.addAppender(appender);

        OptionalInt affected;
        try {
            affected = batchProcessor.recalculateIfEmpty(CALCULATED_AT);
        } finally {
            processorLogger.detachAppender(appender);
        }

        assertThat(affected).isEmpty();
        assertThat(placeStatsRepository.count()).isEqualTo(1);
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(777.0, within(0.000001));
        // 운영자가 "생략, 320행"(정상)과 "생략, 1행"(이상)을 구분할 수 있어야 한다 — 수치가
        // 로그에서 빠지면 생략 분기는 관측 불가능한 사건이 된다.
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("생략")
                        .contains("행 수=1"));
    }

    /**
     * 요청 경로가 쓸 읽기 모델이 배치 결과를 손실 없이 실어 나르는지 본다.
     *
     * <p>엔티티({@link PlaceStats})는 배치 UPSERT 전용이라 생성자를 봉인해 뒀으므로, 요청 경로는
     * JPQL 생성자 표현식으로 뽑는 {@code PlaceStatsView}만 만진다. 그 변환 층이 컬럼을 뒤바꾸거나
     * 값을 깎지 않는지는 <b>배치가 실제로 쓴 행</b>과 대조해야만 확인된다 — 그래서 네이티브 INSERT가
     * 아니라 {@link #runBatch()}를 거친다.
     *
     * <p>점수는 {@link #statsOf}가 읽은 엔티티 값과 대조한다. 상수 1.0을 쓰면 "뷰가 옮겼는가"가
     * 아니라 "배치 계산이 맞는가"를 또 한 번 보는 셈이고, 그건 이미 위쪽 테스트들의 몫이다.
     *
     * <p><b>북마크를 2건 넣는 이유 — 줄이지 말 것.</b> 1건이면 {@code bookmarkCount}가 1이 되는데
     * 시드 첫 장소의 id도 1이라({@code V2__create_initial_data.sql}) 두 컴포넌트의 기대값이
     * 우연히 같아진다. 그러면 이 둘을 뒤바꾸는 회귀를 <b>값으로는</b> 구분할 수 없다.
     * 지금은 {@code Long}/{@code int} 타입 차이 덕에 하이버네이트가 부팅 시
     * {@code SemanticException: Missing constructor}로 걸러 주지만(실측), 그건 record 컴포넌트
     * 타입에 딸린 우연이지 이 테스트가 보장하는 성질이 아니다 — 나중에 둘 다 {@code long}이 되면
     * 그 그물이 사라진다. 2건이면 기대값이 2와 1로 갈려 값만으로 구분된다.
     */
    @Test
    void 뷰_조회는_배치가_저장한_점수와_카운트와_기준시각을_그대로_돌려준다() {
        insertBookmark(placeA, 0);
        insertBookmark(placeA, 90);   // 호출마다 새 유저를 만들므로 uk_bookmark_user_target 충돌 없음
        runBatch();

        List<PlaceStatsView> views = placeStatsRepository.findViewsByPlaceIds(List.of(placeA));

        assertThat(views).hasSize(1);
        PlaceStatsView view = views.get(0);
        assertThat(view.placeId()).isEqualTo(placeA);
        assertThat(view.score()).isEqualTo(statsOf(placeA).getPopularScore().doubleValue());
        assertThat(view.bookmarkCount()).isEqualTo(2);
        assertThat(view.calculatedAt()).isEqualTo(CALCULATED_AT);
    }

    @Test
    void 배치는_모든_장소에_대해_행을_남긴다() {
        long placeCount = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM places")
                .getSingleResult()).longValue();

        runBatch();

        assertThat(placeStatsRepository.count()).isEqualTo(placeCount);
    }

    /**
     * 전체 place_stats를 문자열로 직렬화 — 멱등성 비교용.
     *
     * <p>GROUP_CONCAT은 max_len을 넘으면 <em>조용히 잘린다</em>. 잘린 두 문자열은 서로 같아서
     * 비교가 무의미하게 통과하고, place_stats가 비어 있으면 {@code "null"} 두 개를 비교하게 된다.
     * 두 경우 모두 여기서 막는다.
     */
    private String snapshotOfAllStats() {
        em.createNativeQuery("SET SESSION group_concat_max_len = 1000000").executeUpdate();
        Object result = em.createNativeQuery("""
                SELECT GROUP_CONCAT(
                           CONCAT_WS('|', place_id, town_id, active, popular_score,
                                     bookmark_count, review_count,
                                     IFNULL(avg_rating, 'NULL'), calculated_at)
                           ORDER BY place_id SEPARATOR ';')
                FROM place_stats
                """).getSingleResult();
        em.clear();

        String snapshot = String.valueOf(result);
        long rowCount = placeStatsRepository.count();
        assertThat(rowCount).isPositive();
        assertThat(snapshot).isNotEqualTo("null");
        // 잘림 감지: 세그먼트 수가 실제 행 수와 같아야 한다
        assertThat(snapshot.split(";", -1)).hasSize((int) rowCount);
        return snapshot;
    }
}
