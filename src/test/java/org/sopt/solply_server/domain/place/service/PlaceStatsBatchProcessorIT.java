package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.sopt.solply_server.domain.place.entity.PlaceStatsId;
import org.sopt.solply_server.domain.place.repository.PlaceStatsMetaRepository;
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
@Import({QueryDslConfig.class, PlaceStatsBatchProcessor.class, PlaceStatsProperties.class,
        PlaceStatsMetaRepository.class})
class PlaceStatsBatchProcessorIT extends MySqlContainerSupport {

    private static final double BOOKMARK_WEIGHT = 1.0;
    private static final double REVIEW_WEIGHT = 2.0;
    private static final double HALF_LIFE_DAYS = 90.0;
    private static final int MIN_REVIEW_COUNT = 5;

    /**
     * 북마크 1건(감쇠 없음)의 점수 = {@code ln(1 + 1)}. 로그 압축 이전에는 그냥 1.0이었다.
     *
     * <p>상수로 뽑아 두는 이유는 이 값이 <b>여러 테스트에 흩어진 같은 사실</b>이기 때문이다 —
     * 배율을 조정할 때 리터럴 0.693147을 찾아 다니면 반드시 하나를 놓친다.
     */
    private static final double ONE_FRESH_BOOKMARK = Math.log(2);

    /**
     * <b>리뷰 픽스처는 두 장소 이상에 걸쳐 넣어야 한다.</b> 조정 평점의 기준 {@code C}가 전체
     * 리뷰의 평균이라, 리뷰를 한 장소에만 넣으면 {@code C}가 그 장소의 평균과 같아져
     * 리뷰 항 기여가 <em>항상 0</em>이 된다. 그러면 리뷰 축을 검증한다고 믿는 테스트가
     * 실제로는 아무것도 보지 않는다 (실측으로 확인하고 픽스처를 다시 짰다).
     */
    private static final double SCORE_TOLERANCE = 0.000001;

    /** 배치 기준 시각. 모든 픽스처의 created_at을 이 시각 기준 상대값으로 넣는다. */
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /**
     * 다음 회차의 기준 시각. 세대 경계를 보려면 두 회차의 {@code calculatedAt}이 <b>달라야</b> 한다 —
     * 같은 값으로 두 번 돌리면 그것은 세대 교체가 아니라 같은 세대의 재실행이다(멱등성 테스트의 몫).
     * 운영 간격과 같은 1시간을 준다.
     */
    private static final LocalDateTime NEXT_CALCULATED_AT = CALCULATED_AT.plusHours(1);

    /** 두 회차의 버전. 규약(epoch 초, UTC 간주)은 {@code PlaceStatsMetaRepository#toVersion}이 정한다 */
    private static final long VERSION = CALCULATED_AT.toEpochSecond(ZoneOffset.UTC);
    private static final long NEXT_VERSION = NEXT_CALCULATED_AT.toEpochSecond(ZoneOffset.UTC);

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

    /** {@link #runBatch(double)}의 {@code m}판. 설정 빈을 잠시 바꿔 넣고 되돌리는 이유도 같다. */
    private int runBatchWithMinReviewCount(int minReviewCount) {
        int original = properties.getMinReviewCount();
        properties.setMinReviewCount(minReviewCount);
        try {
            return batchProcessor.recalculateAll(CALCULATED_AT);
        } finally {
            properties.setMinReviewCount(original);
        }
    }

    /**
     * 기준 시각을 지정해 돌린다. {@link #runBatch()}가 상수를 쓰는 것과 달리, 세대 교체를 보는
     * 테스트는 두 회차의 기준 시각이 달라야 성립하므로 호출자가 정한다.
     */
    private int runBatchAt(LocalDateTime calculatedAt) {
        return batchProcessor.recalculateAll(calculatedAt);
    }

    /** 현 회차({@link #CALCULATED_AT}) 버전의 행 */
    private PlaceStats statsOf(long placeId) {
        return statsOf(placeId, VERSION);
    }

    private PlaceStats statsOf(long placeId, long version) {
        return placeStatsRepository.findById(new PlaceStatsId(placeId, version)).orElseThrow();
    }

    private boolean existsStats(long placeId, long version) {
        return placeStatsRepository.findById(new PlaceStatsId(placeId, version)).isPresent();
    }

    /**
     * 세대 메타를 초기 상태(둘 다 NULL)로 되돌린다 — {@link #clearStats()}와 같은 이유다.
     * 같은 클래스의 {@code 배치_트랜잭션은_READ_COMMITTED로_열린다}가 배치 결과를 <b>실제로 커밋</b>하므로
     * 그 테스트가 먼저 돌면 메타에 값이 남아 "첫 배치 직후 prev는 NULL"이라는 단언이 성립하지 않는다.
     * 이 UPDATE는 테스트 트랜잭션과 함께 롤백된다.
     */
    private void resetGenerationMeta() {
        em.createNativeQuery("""
                UPDATE place_stats_meta
                   SET current_generation = NULL, prev_generation = NULL
                 WHERE id = 1
                """).executeUpdate();
    }

    /** 버전 메타 컬럼 하나. BIGINT라 드라이버가 어떤 Number로 주든 받는다 (없으면 null) */
    private Long generationOf(String column) {
        Object value = em.createNativeQuery(
                "SELECT " + column + " FROM place_stats_meta WHERE id = 1").getSingleResult();
        return value == null ? null : ((Number) value).longValue();
    }

    private long versionRowCount(long version) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM place_stats WHERE version = :version")
                .setParameter("version", version)
                .getSingleResult()).longValue();
    }

    /**
     * <b>한 회차의 계약 셋 — 새 버전 적재 · 메타 시프트 · 옛 버전 청소.</b> 셋이 한 트랜잭션에
     * 있어야 커서가 가리키는 버전이 항상 실재한다.
     *
     * <p>3회차까지 도는 이유: 2회차만으로는 청소가 통째로 빠져도 그린이다(지울 옛 버전이 아직
     * 없다). 3회차에서 1회차 버전이 사라지고 2·3회차 것만 남는 것이 보관 2버전의 정의다.
     *
     * <p>회차마다 북마크를 더해 점수를 갈라 두면 "새 버전을 만든 게 아니라 같은 행을 덮었다"는
     * 회귀가 값으로도 드러난다.
     */
    @Test
    void 배치는_새_버전을_적재하고_메타를_밀고_옛_버전을_지운다() {
        clearStats();
        resetGenerationMeta();
        insertBookmark(placeA, 0);

        runBatchAt(CALCULATED_AT);

        assertThat(generationOf("current_generation")).isEqualTo(VERSION);
        assertThat(generationOf("prev_generation")).isNull();
        assertThat(versionRowCount(VERSION)).isEqualTo(activePlaceCount());
        BigDecimal firstScore = statsOf(placeA, VERSION).getPopularScore();

        insertBookmark(placeA, 0);   // 2회차 점수를 1회차와 갈라놓는 조각
        runBatchAt(NEXT_CALCULATED_AT);

        assertThat(generationOf("current_generation")).isEqualTo(NEXT_VERSION);
        assertThat(generationOf("prev_generation")).isEqualTo(VERSION);
        // 두 버전이 나란히 산다 — 1회차 값이 그대로 남아 있어야 그 커서가 계속 서빙된다
        assertThat(statsOf(placeA, VERSION).getPopularScore()).isEqualByComparingTo(firstScore);
        assertThat(statsOf(placeA, NEXT_VERSION).getPopularScore())
                .isNotEqualByComparingTo(firstScore);

        long thirdVersion = PlaceStatsMetaRepository.toVersion(CALCULATED_AT.plusHours(2));
        runBatchAt(CALCULATED_AT.plusHours(2));

        // 보관은 2버전 — 1회차가 통째로 죽는다
        assertThat(versionRowCount(VERSION)).isZero();
        assertThat(versionRowCount(NEXT_VERSION)).isEqualTo(activePlaceCount());
        assertThat(versionRowCount(thirdVersion)).isEqualTo(activePlaceCount());
    }

    @Test
    void 오늘_생긴_북마크는_감쇠_없이_로그_압축만_거친다() {
        insertBookmark(placeA, 0);

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(ONE_FRESH_BOOKMARK, within(SCORE_TOLERANCE));
        assertThat(statsOf(placeA).getBookmarkCount()).isEqualTo(1);
    }

    /**
     * <b>반감기의 의미는 로그 압축 뒤에도 그대로다 — 90일 지난 북마크 2건 = 오늘 북마크 1건.</b>
     *
     * <p>점수 절대값이 아니라 <em>두 장소를 견주는</em> 형태인 것이 핵심이다. 로그가 씌워지면서
     * "절반만 반영"이 더는 점수의 절반이 아니게 됐고({@code ln(1.5) ≠ ln(2)/2}),
     * 그렇다고 {@code ln(1.5)}라는 리터럴만 박아 두면 그 숫자가 반감기에서 나왔다는 사실이
     * 코드에서 사라진다. 감쇠 합이 같으면 점수가 같다는 등식이라야 반감기를 직접 겨눈다.
     *
     * <p>반감기가 빠지면 2건 쪽이 {@code ln(3)}으로 떠올라 깨진다.
     */
    @Test
    void 반감기가_지난_북마크는_절반의_무게로_합산된다() {
        insertBookmark(placeA, 0);      // 감쇠 합 1.0
        insertBookmark(placeB, 90);     // 감쇠 합 0.5 + 0.5 = 1.0
        insertBookmark(placeB, 90);

        runBatch();

        assertThat(statsOf(placeB).getPopularScore())
                .isEqualByComparingTo(statsOf(placeA).getPopularScore());
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(ONE_FRESH_BOOKMARK, within(SCORE_TOLERANCE));
    }

    /**
     * <b>중심이 상수 3점이 아니라 전체 평균 {@code C}다.</b> 여기서는 세 리뷰(5·3·1)의 평균이
     * 정확히 3.0이라 옛 공식과 부호가 같지만, 크기는 베이지안 보정에 눌려 훨씬 작다 —
     * 리뷰 1건은 {@code m=5}에 5:1로 밀려 편차의 1/6만 남긴다.
     *
     * <p>세 장소에 나눠 넣는 것이 이 테스트의 전제다. 한 장소에 몰면 {@code C}가 그 장소의
     * 평균과 같아져 셋 다 0이 된다.
     */
    @Test
    void 평점은_전체_평균을_중심으로_가감된다() {
        insertReview(placeA, 5, 0);   // C = (5+3+1)/3 = 3.0
        insertReview(placeB, 3, 0);
        insertReview(placeC, 1, 0);

        runBatch();

        // 조정평점 = (5 + 5×3)/(1+5) = 3.3333 → (3.3333 − 3.0) × 2
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.666667, within(SCORE_TOLERANCE));
        assertThat(statsOf(placeB).getPopularScore().doubleValue())
                .isCloseTo(0.0, within(SCORE_TOLERANCE));
        assertThat(statsOf(placeC).getPopularScore().doubleValue())
                .isCloseTo(-0.666667, within(SCORE_TOLERANCE));
    }

    /**
     * <b>리뷰 항에는 감쇠가 없다.</b> 같은 평점이면 언제 쓰인 리뷰든 기여가 같다 — 평판은
     * 시점 무관한 누적 판단이라 1건이 1표씩 들어간다는 것이 공식의 주장이다.
     *
     * <p>감쇠가 살아 있으면 반감기(90일)만큼 지난 쪽만 절반으로 깎여 6.0 대 3.0으로 갈린다.
     * 두 장소를 <em>서로</em> 비교하는 것이 핵심이다 — 절대값만 보면 가중치가 통째로 바뀌는
     * 회귀와 구분되지 않는다.
     */
    @Test
    void 리뷰_기여는_작성_시점과_무관하다() {
        insertReview(placeA, 5, 0);    // 기준 시각 정각
        insertReview(placeB, 5, 90);   // 반감기만큼 지난 리뷰
        // placeC의 2점은 C를 4.0으로 끌어내려 위 둘의 기여가 0이 되는 것을 막는다.
        // 이것이 없으면 C = 5.0이 되어 두 점수가 "같다"는 단언이 0 = 0으로 공허하게 통과한다.
        insertReview(placeC, 2, 0);

        runBatch();

        assertThat(statsOf(placeB).getPopularScore())
                .isEqualByComparingTo(statsOf(placeA).getPopularScore());
        // 조정평점 = (5 + 5×4)/(1+5) = 4.1667 → (4.1667 − 4.0) × 2
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.333333, within(SCORE_TOLERANCE));
    }

    /**
     * <b>저평점이 점수를 끌어내리는 성질은 중심화가 3점에서 {@code C}로 바뀐 뒤에도 유지된다.</b>
     * 리뷰에는 감쇠가 없으므로 180일이 지나도 페널티가 깎이지 않는다.
     *
     * <p>북마크만 있을 때({@link #ONE_FRESH_BOOKMARK})와 견주는 형태인 것이 핵심이다.
     * 중심화가 사라지면 리뷰가 점수를 <em>올려</em> 그 기준선 위로 올라간다.
     */
    @Test
    void 오래된_저평점_리뷰도_점수를_끌어내린다() {
        insertBookmark(placeA, 0);
        insertReview(placeA, 1, 180);   // 180일이 지나도 감쇠하지 않는다
        insertReview(placeB, 5, 0);     // C = (1+5)/2 = 3.0

        runBatch();

        double score = statsOf(placeA).getPopularScore().doubleValue();
        assertThat(score).isLessThan(ONE_FRESH_BOOKMARK);
        // ln(2) + ((1 + 5×3)/(1+5) − 3.0) × 2 = 0.693147 − 0.666667
        assertThat(score).isCloseTo(0.026481, within(SCORE_TOLERANCE));
    }

    @Test
    void 북마크_점수와_리뷰_점수는_합산된다() {
        insertBookmark(placeA, 0);    // 감쇠 합 1.0
        insertBookmark(placeA, 90);   // + 0.5 (북마크는 감쇠한다)
        insertReview(placeA, 4, 90);  // 리뷰는 감쇠하지 않는다
        insertReview(placeB, 2, 0);   // C = (4+2)/2 = 3.0

        runBatch();

        PlaceStats stats = statsOf(placeA);
        // ln(1 + 1.5) + ((4 + 5×3)/(1+5) − 3.0) × 2 = 0.916291 + 0.333333
        assertThat(stats.getPopularScore().doubleValue())
                .isCloseTo(1.249624, within(SCORE_TOLERANCE));
        assertThat(stats.getBookmarkCount()).isEqualTo(2);
        assertThat(stats.getReviewCount()).isEqualTo(1);
        assertThat(stats.getAvgRating().doubleValue()).isCloseTo(4.0, within(0.005));
    }

    /**
     * <b>리뷰가 단 한 건도 없으면 {@code C}가 NULL이다.</b> {@code COALESCE(AVG(rating), 3.0)}가
     * 없으면 여기서 전 장소의 점수가 NULL이 되고 {@code NOT NULL} 컬럼이라 배치 자체가 터진다 —
     * 0점이 아니라 <em>예외</em>로 실패하므로 이 테스트가 그 폴백을 지킨다.
     */
    @Test
    void 활동이_없는_장소는_0점_행으로_기록된다() {
        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getPopularScore().doubleValue()).isCloseTo(0.0, within(SCORE_TOLERANCE));
        assertThat(stats.getBookmarkCount()).isZero();
        assertThat(stats.getReviewCount()).isZero();
        assertThat(stats.getAvgRating()).isNull();
    }

    /**
     * 위 테스트의 짝 — <b>남들에게 리뷰가 있어도</b> 내 리뷰가 0건이면 리뷰 항 기여가 정확히 0이다.
     * 조정 평점이 {@code (0 + m·C)/(0 + m) = C}가 되어 중심화에서 상쇄되기 때문이고,
     * 이것이 리뷰 없는 장소가 순위에서 벌도 상도 받지 않는 근거다.
     *
     * <p>중심화({@code − C})를 지우면 리뷰가 없는 장소가 {@code w₂ × C}만큼 공짜 점수를 받아
     * 여기서 깨진다. 활동이 전무한 위 테스트로는 그 회귀가 잡히지 않는다 — 그때는 {@code C}가
     * 폴백 상수라 어느 쪽이든 상수가 되기 때문이다.
     */
    @Test
    void 리뷰가_없는_장소는_남의_리뷰에_영향받지_않는다() {
        insertReview(placeB, 5, 0);
        insertReview(placeC, 1, 0);

        runBatch();

        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.0, within(SCORE_TOLERANCE));
        assertThat(statsOf(placeA).getReviewCount()).isZero();
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
     * <p>이 상한이 없으면 {@code TIMESTAMPDIFF}가 음수가 되어 {@code POW(0.5, 음수) > 1} —
     * 감쇠가 아니라 증폭이다. 상한이 없을 때 아래 시나리오는 {@code 1.0}이 아니라
     * {@code 2.0002...}가 나온다. 멱등성 주장도 이 상한 위에 서 있다
     * ({@code 두_실행_사이에_기준시각_이후_활동이_들어와도_결과가_같다}).
     */
    @Test
    void 기준시각_이후에_생긴_북마크는_집계에_들어가지_않는다() {
        insertBookmark(placeA, 0);                        // 기준 시각 정각 → +1.0, 카운트 1
        insertBookmarkAfterCalculatedAt(placeA, 30);      // 30분 뒤 → 무시돼야 한다

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
        // 증폭까지 잡으려면 점수를 정확히 봐야 한다 — 카운트만 보면 POW 쪽 회귀를 놓친다
        assertThat(stats.getPopularScore().doubleValue())
                .isCloseTo(ONE_FRESH_BOOKMARK, within(SCORE_TOLERANCE));
    }

    /**
     * 리뷰 축에도 같은 상한이 걸려 있어야 한다 — 점수·건수·평균 평점 셋 모두 영향을 받는다.
     *
     * <p><b>placeB의 리뷰가 이 테스트를 성립시킨다.</b> 없으면 상한을 지켰을 때 {@code C = 5.0},
     * 어겼을 때 {@code C = 3.0}이 되는데 <em>두 경우 모두 조정평점이 {@code C}와 같아져</em>
     * 점수가 0으로 일치한다. 즉 점수 단언이 상한 위반을 구분하지 못한다.
     * placeB가 {@code C}를 붙들어 두면 0.333333 대 0.0으로 갈린다.
     */
    @Test
    void 기준시각_이후에_생긴_리뷰는_집계에_들어가지_않는다() {
        insertReview(placeA, 5, 0);
        insertReview(placeB, 3, 0);                       // C를 고정하는 대조군
        insertReviewAfterCalculatedAt(placeA, 1, 30);     // 무시돼야 한다

        runBatch();

        PlaceStats stats = statsOf(placeA);
        assertThat(stats.getReviewCount()).isEqualTo(1);
        assertThat(stats.getAvgRating().doubleValue()).isCloseTo(5.0, within(0.005));
        // 유효 리뷰는 5·3 → C = 4.0. 조정평점 = (5 + 5×4)/6 = 4.1667
        assertThat(stats.getPopularScore().doubleValue())
                .isCloseTo(0.333333, within(SCORE_TOLERANCE));
    }

    /**
     * 상한이 {@code <}가 아니라 {@code <=}인 것을 못 박는다. {@code calculatedAt}과 정확히 같은
     * 시각의 활동은 포함돼야 한다 — {@code <}로 바꾸면 배치를 같은 기준 시각으로 아무리 다시 돌려도
     * 경계값 1건이 영영 집계되지 않는다.
     */
    @Test
    void 기준시각과_정확히_같은_시각의_북마크는_포함된다() {
        insertBookmark(placeA, 0);   // created_at == CALCULATED_AT

        runBatch();

        assertThat(statsOf(placeA).getBookmarkCount()).isEqualTo(1);
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(ONE_FRESH_BOOKMARK, within(SCORE_TOLERANCE));
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
        assertThat(before.getPopularScore().doubleValue())
                .isCloseTo(ONE_FRESH_BOOKMARK, within(SCORE_TOLERANCE));
        assertThat(before.getBookmarkCount()).isEqualTo(1);
        assertThat(before.getReviewCount()).isZero();
        assertThat(before.getAvgRating()).isNull();

        // 1회차 이후 원본이 늘었다 — 북마크 +1, 리뷰 +1. placeB의 1점이 C를 3.0으로 붙든다
        insertBookmark(placeA, 0);
        insertReview(placeA, 5, 0);
        insertReview(placeB, 1, 0);

        runBatch();

        PlaceStats after = statsOf(placeA);
        // ln(1 + 2) + ((5 + 5×3)/(1+5) − 3.0) × 2 = 1.098612 + 0.666667
        assertThat(after.getPopularScore().doubleValue())
                .isCloseTo(1.765279, within(SCORE_TOLERANCE));
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
        assertThat(stats.getPopularScore().doubleValue())
                .isCloseTo(ONE_FRESH_BOOKMARK, within(SCORE_TOLERANCE));
        assertThat(stats.getBookmarkCount()).isEqualTo(1);
    }

    /**
     * town_id는 places에서 비정규화해 오는 값이고, 낡으면 순위가 아니라 소속이 틀린다
     * (V24·PlaceStats 주석 참고). 정렬 인덱스의 선행 컬럼이라 배치가 이 값을 놓치면
     * 목록 경로가 통째로 어긋난다.
     *
     * <p><b>{@code active} 축은 V26에서 사라졌다 (2026-08-02).</b> 여기에 있던
     * "비활성 장소를 만들어 active=false 복사 경로를 태운다"는 절차도 함께 걷어냈다 —
     * 활성 여부의 진실은 이제 {@code places.active} 하나이고, 조회의 조인 가드가 그것을 본다.
     */
    @Test
    void 장소의_town_id를_그대로_복사한다() {
        long expectedTownId = ((Number) em.createNativeQuery(
                "SELECT town_id FROM places WHERE id = :id")
                .setParameter("id", placeA)
                .getSingleResult()).longValue();

        runBatch();

        assertThat(statsOf(placeA).getTownId()).isEqualTo(expectedTownId);
    }

    /**
     * <b>불변식의 절반 — 필터.</b> 비활성 장소는 새 회차에 아예 들어가지 않는다.
     * 이것이 인기순 쿼리가 places 조인 없이 서빙되는 근거다(설계 §3).
     */
    @Test
    void 배치는_비활성_장소에_행을_만들지_않는다() {
        clearStats();
        setActive(placeC, false);

        runBatch();

        assertThat(existsStats(placeC, VERSION)).isFalse();
        assertThat(existsStats(placeA, VERSION)).isTrue();
    }

    /**
     * <b>불변식의 나머지 절반 — 잔행은 옛 버전이 죽을 때 함께 사라진다.</b> 비활성 장소를 골라
     * 지우는 삭제가 따로 필요 없는 이유다(설계 §3).
     *
     * <p><b>직전 버전에는 계속 남는 것이 오답이 아니다.</b> 그 버전에 고정된 스크롤에는 보여야
     * 하고, 그것이 스냅샷의 올바른 의미다. 비활성화가 실제로 사라지는 시점은 그 버전이 보관
     * 밖으로 밀려나는 <b>두 회차 뒤</b>다.
     */
    @Test
    void 옛_버전이_죽으면_비활성_장소의_잔행도_함께_사라진다() {
        clearStats();
        runBatch();
        assertThat(existsStats(placeC, VERSION)).isTrue();   // 잔행을 만들어 둔다

        setActive(placeC, false);
        runBatchAt(NEXT_CALCULATED_AT);

        assertThat(existsStats(placeC, NEXT_VERSION)).isFalse();   // 새 버전에는 없다
        assertThat(existsStats(placeC, VERSION)).isTrue();         // 직전 버전 스냅샷에는 남는다

        runBatchAt(CALCULATED_AT.plusHours(2));

        assertThat(versionRowCount(VERSION)).isZero();
        assertThat(existsStats(placeA, NEXT_VERSION)).isTrue();
    }

    /**
     * 재활성화도 대칭이다 — 삭제가 영구 배제가 아니라 <b>그 회차의 상태 반영</b>임을 못 박는다.
     * 삭제를 "지운 뒤 다시 안 만든다"로 구현하면 여기서 깨진다.
     */
    @Test
    void 재활성화한_장소는_다음_배치에_복귀한다() {
        clearStats();
        setActive(placeC, false);
        runBatch();
        assertThat(existsStats(placeC, VERSION)).isFalse();

        setActive(placeC, true);
        runBatchAt(NEXT_CALCULATED_AT);

        assertThat(existsStats(placeC, NEXT_VERSION)).isTrue();
    }

    /** 테스트 트랜잭션과 함께 롤백되므로 시드 장소의 상태를 영구히 바꾸지 않는다 */
    private void setActive(long placeId, boolean active) {
        em.createNativeQuery("UPDATE places SET active = :active WHERE id = :id")
                .setParameter("active", active)
                .setParameter("id", placeId)
                .executeUpdate();
        em.clear();
    }

    /**
     * 다른 테스트가 전부 반감기 90일 하나만 써서, SQL에 90이 하드코딩돼 있어도 전부 통과한다.
     * 설정값이 실제로 쿼리까지 전달되는지 보려면 다른 반감기가 하나는 있어야 한다.
     */
    @Test
    void 반감기_설정값이_실제_계산에_반영된다() {
        insertBookmark(placeA, 45);

        runBatch(45.0);   // 반감기를 45일로 주면 45일 전 활동이 정확히 절반이 된다

        // ln(1 + 0.5). 반감기 90일이었다면 0.5^(45/90) = 0.7071 → ln(1.7071) = 0.534800
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(0.405465, within(SCORE_TOLERANCE));
    }

    /**
     * {@code m}도 반감기와 같은 이유로 설정 주입 경로를 따로 본다 — 다른 테스트가 전부 5 하나만
     * 써서 SQL에 5가 하드코딩돼 있어도 통과한다.
     *
     * <p>{@code m = 1}이면 리뷰 1건짜리 장소의 자기 평점이 절반까지 반영돼 기여가 두 배 넘게 뛴다
     * ({@code m = 5}일 때 0.666667 → {@code m = 1}일 때 2.0).
     */
    @Test
    void 최소_리뷰_수_설정값이_실제_계산에_반영된다() {
        insertReview(placeA, 5, 0);
        insertReview(placeB, 1, 0);   // C = 3.0

        runBatchWithMinReviewCount(1);

        // 조정평점 = (5 + 1×3)/(1+1) = 4.0 → (4.0 − 3.0) × 2
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(2.0, within(SCORE_TOLERANCE));
    }

    /**
     * <b>로그 압축이 두 축의 자릿수를 맞춘다는 주장을 값으로 못 박는다.</b> 북마크가 11배 많은
     * 장소를, 리뷰가 충분히 쌓인 고평점 장소가 앞선다.
     *
     * <p>로그 압축이 빠지면 A가 {@code 11 − 1.0 = 10.0}, B가 {@code 1 + 1.0 = 2.0}으로 갈려
     * A의 압승이 된다 — 즉 이 테스트는 {@code LN}을 지우는 회귀를 직접 겨눈다.
     *
     * <p><b>리뷰를 5건씩 넣는 것이 이 테스트의 전제다.</b> 1건이면 {@code m = 5}에 5:1로 눌려
     * 기여가 ±0.333에 그쳐 북마크 11배 차이(약 1.79)를 넘지 못한다. 그것이 오답이 아니라
     * 베이지안 평균의 의도다 — 표본이 적은 평점은 순위를 움직일 자격이 없다.
     */
    @Test
    void 리뷰가_쌓이면_평점이_북마크_열한_배_차이를_뒤집는다() {
        for (int i = 0; i < 11; i++) {
            insertBookmark(placeA, 0);
        }
        insertBookmark(placeB, 0);
        for (int i = 0; i < 5; i++) {
            insertReview(placeA, 3, 0);
            insertReview(placeB, 5, 0);   // C = (3×5 + 5×5)/10 = 4.0
        }

        runBatch();

        double scoreA = statsOf(placeA).getPopularScore().doubleValue();
        double scoreB = statsOf(placeB).getPopularScore().doubleValue();
        assertThat(scoreB).isGreaterThan(scoreA);
        // A: ln(12) + ((15 + 5×4)/10 − 4.0) × 2 = 2.484907 − 1.0
        assertThat(scoreA).isCloseTo(1.484907, within(SCORE_TOLERANCE));
        // B: ln(2)  + ((25 + 5×4)/10 − 4.0) × 2 = 0.693147 + 1.0
        assertThat(scoreB).isCloseTo(1.693147, within(SCORE_TOLERANCE));
    }

    @Test
    void 기본_설정값은_설계에서_정한_가중치와_반감기다() {
        assertThat(properties.getBookmarkWeight()).isEqualTo(BOOKMARK_WEIGHT);
        assertThat(properties.getReviewWeight()).isEqualTo(REVIEW_WEIGHT);
        assertThat(properties.getHalfLifeDays()).isEqualTo(HALF_LIFE_DAYS);
        assertThat(properties.getMinReviewCount()).isEqualTo(MIN_REVIEW_COUNT);
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
     * 걸려 있다</b> — Flyway 백필을 기각한 이유가 "RR에서 {@code bookmarks} 전 행에
     * next-key 락(행 10,399,466 / 락 10,730,488건 — 갭 몫 포함)"이었으므로, 여기서 RC가
     * 사라지면 부팅 시 정확히 그 장애 모드가 재현된다.
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
            // 세대 메타도 함께 되돌린다. 커밋한 배치가 여기에 자기 회차의 calculatedAt을 남기는데,
            // 그대로 두면 뒤따르는 IT가 "아직 배치가 안 돈" 상태를 전제할 수 없다.
            // place_stats처럼 DELETE하지 않는 것은 이것이 1행 레지스터이기 때문이다(V28의 CHECK).
            st.executeUpdate("""
                    UPDATE place_stats_meta
                       SET current_generation = NULL, prev_generation = NULL
                     WHERE id = 1
                    """);
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
        long placeCount = activePlaceCount();

        OptionalInt affected = batchProcessor.recalculateIfEmpty(CALCULATED_AT);

        // 빈 테이블이라 전부 INSERT 경로 → MySQL이 INSERT를 1로 세므로 영향 행 수 = 장소 수.
        // "재계산했고 영향 행 0"과 "건너뜀"이 다른 사실이라는 것을 값으로도 못 박는다.
        assertThat(affected).hasValue((int) placeCount);
        assertThat(placeStatsRepository.count()).isEqualTo(placeCount);
        assertThat(statsOf(placeA).getPopularScore().doubleValue())
                .isCloseTo(ONE_FRESH_BOOKMARK, within(SCORE_TOLERANCE));
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
                    (place_id, version, town_id, popular_score,
                     bookmark_count, review_count, avg_rating)
                SELECT p.id, :version, p.town_id, 777.000000, 0, 0, NULL
                FROM places p WHERE p.id = :placeId
                """)
                .setParameter("placeId", placeA)
                .setParameter("version", VERSION)
                .executeUpdate();
        insertBookmark(placeA, 0);   // 재계산이 돌면 점수가 777이 아니라 ln(2)가 된다

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
    void 뷰_조회는_배치가_저장한_점수와_카운트를_그대로_돌려준다() {
        insertBookmark(placeA, 0);
        insertBookmark(placeA, 90);   // 호출마다 새 유저를 만들므로 uk_bookmark_user_target 충돌 없음
        runBatch();

        List<PlaceStatsView> views = placeStatsRepository.findViewsByPlaceIds(List.of(placeA));

        assertThat(views).hasSize(1);
        PlaceStatsView view = views.get(0);
        assertThat(view.placeId()).isEqualTo(placeA);
        assertThat(view.score()).isEqualTo(statsOf(placeA).getPopularScore().doubleValue());
        assertThat(view.bookmarkCount()).isEqualTo(2);
    }

    /** "모든 장소"가 아니라 <b>모든 활성 장소</b>다 — 그 차이가 곧 조회의 불변식이다 */
    @Test
    void 배치는_모든_활성_장소에_대해_행을_남긴다() {
        clearStats();
        setActive(placeC, false);
        long activeCount = activePlaceCount();

        runBatch();

        assertThat(placeStatsRepository.count()).isEqualTo(activeCount);
    }

    private long activePlaceCount() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM places WHERE active = 1")
                .getSingleResult()).longValue();
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
                           CONCAT_WS('|', place_id, version, town_id, popular_score,
                                     bookmark_count, review_count,
                                     IFNULL(avg_rating, 'NULL'))
                           ORDER BY version, place_id SEPARATOR ';')
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
