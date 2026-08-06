package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.repository.PlaceStatsMetaRepository;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.global.config.QueryDslConfig;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>한 회차의 세 문장(새 버전 적재 · 메타 시프트 · 옛 버전 청소)이 정말 하나로 죽는가</b>를 본다.
 *
 * <p>{@code PlaceStatsBatchProcessorIT}는 성공 경로만 밟으므로 세 문장이 각자 다른 트랜잭션으로
 * 갈라져도 전부 그린이다. 갈라진 배치가 실제로 만드는 것은 <b>행 집합과 메타가 어긋난 구간</b>이고,
 * 그 구간에 발급된 커서는 존재하지 않는 버전을 가리킨다. 그 사고는 중간 실패로만 드러난다.
 *
 * <p><b>모든 테스트가 {@code NOT_SUPPORTED}인 이유 — 반드시 읽을 것.</b> 검증 대상이 "프로세서의
 * 트랜잭션이 롤백되는가"인데, {@code @DataJpaTest}의 테스트 트랜잭션 안에서 부르면 프로세서는
 * <em>그 트랜잭션에 참여</em>할 뿐이라 롤백 주체가 테스트가 되고, 게다가 참여 트랜잭션에서 예외가
 * 나면 스프링이 바깥을 rollback-only로 표시해 이후 단언용 조회조차 성립하지 않는다. 바깥
 * 트랜잭션을 걷어내야만 프로세서가 자기 트랜잭션을 열고 스스로 롤백한다 (운영의 스케줄러 경로와 동일).
 * 그래서 픽스처도 단언도 <b>커밋된 상태</b>를 직접 읽어야 하며, 정리는 {@link #resetStats()}가 맡는다.
 *
 * <p>실패 주입에 스파이를 쓰는 것은 제품 코드에 실패 훅을 내지 않기 위해서다 — 세 문장의 순서와
 * 경계는 {@code PlaceStatsBatchProcessor}가 소유해야 하고, 테스트가 그 안에 갈고리를 박으면
 * 검증 대상이 제품 구조가 아니라 갈고리가 된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, PlaceStatsBatchProcessor.class, PlaceStatsProperties.class,
        PlaceStatsMetaRepository.class})
class PlaceStatsBatchAtomicityIT extends MySqlContainerSupport {

    /** 1회차. 픽스처의 두 회차와 실패할 3회차가 모두 이 시각 기준 상대값이다. */
    private static final LocalDateTime FIRST_CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** 2회차. 운영 간격과 같은 1시간 뒤 — 여기까지가 "정상적으로 쌓인 기존 상태"다. */
    private static final LocalDateTime SECOND_CALCULATED_AT = FIRST_CALCULATED_AT.plusHours(1);

    /** 중간에 죽을 3회차. 이 회차의 흔적이 하나라도 남으면 원자성이 깨진 것이다. */
    private static final LocalDateTime FAILING_CALCULATED_AT = FIRST_CALCULATED_AT.plusHours(2);

    private static final long FIRST_VERSION = PlaceStatsMetaRepository.toVersion(FIRST_CALCULATED_AT);
    private static final long SECOND_VERSION =
            PlaceStatsMetaRepository.toVersion(SECOND_CALCULATED_AT);
    private static final long FAILING_VERSION =
            PlaceStatsMetaRepository.toVersion(FAILING_CALCULATED_AT);

    /** 주입한 실패임을 로그·스택트레이스에서 곧바로 알아보게 하는 표식 */
    private static final String INJECTED = "원자성 검증용 주입 실패";

    @DynamicPropertySource
    static void ddlAuto(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    PlaceStatsBatchProcessor batchProcessor;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** 3단계 중 1·3단계(적재·청소)의 주체. 청소를 실패시키고, 적재가 실제로 돌았는지도 여기서 센다. */
    @SpyBean
    PlaceStatsRepository placeStatsRepository;

    /** 2단계(메타 시프트)의 주체 */
    @SpyBean
    PlaceStatsMetaRepository placeStatsMetaRepository;

    /**
     * 기존 상태를 <b>실제 배치 두 회차로</b> 만든다. 네이티브 INSERT로 흉내 내지 않는 이유는,
     * 롤백 뒤에 남아야 할 것이 "행 두 벌"이 아니라 <b>배치가 만든 그대로의 두 벌</b>이기 때문이다.
     * 이 픽스처가 곧 스냅샷의 기준값이 된다.
     */
    @BeforeEach
    void 두_회차를_커밋해_기존_상태를_만든다() {
        resetStats();
        batchProcessor.recalculateAll(FIRST_CALCULATED_AT);
        batchProcessor.recalculateAll(SECOND_CALCULATED_AT);
    }

    /**
     * 이 클래스의 쓰기는 전부 커밋된다. 지우지 않으면 같은 싱글턴 컨테이너를 쓰는 다른 IT가
     * place_stats가 비어 있음을 전제할 수 없다 ({@code PlaceStatsRepositoryIT} 등).
     */
    @AfterEach
    void 커밋된_상태를_지운다() {
        resetStats();
    }

    /**
     * <b>1단계 성공 · 2단계 실패.</b> 새 버전 행은 이미 INSERT됐지만 메타 시프트가 죽는다.
     *
     * <p>세 문장이 갈라져 있으면 새 버전 행 집합만 남고 메타는 2회차를 가리키는 상태가 커밋된다 —
     * 그 상태에서 3회차는 <b>아무도 못 보는 유령 행</b>이고, 다음 회차의 청소 판정도 이 잘못된
     * 메타 위에서 돌아간다. 그래서 흔적이 0이어야 한다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 메타_시프트가_실패하면_새_버전_적재도_함께_롤백된다() {
        String before = snapshotOfAllStats();
        doThrow(new IllegalStateException(INJECTED))
                .when(spiedMetaRepository()).shiftGeneration(FAILING_VERSION);

        assertThatThrownBy(() -> batchProcessor.recalculateAll(FAILING_CALCULATED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(INJECTED);

        // 전제 확인: 1단계는 실제로 돌았다. 적재가 아예 안 일어났다면 "롤백됐다"는 단언이 공허하다.
        verify(spiedStatsRepository())
                .upsertAll(eq(FAILING_VERSION), eq(FAILING_CALCULATED_AT),
                        anyDouble(), anyDouble(), anyDouble(), anyInt());
        assertThat(versionRowCount(FAILING_VERSION)).isZero();
        assertThat(snapshotOfAllStats()).isEqualTo(before);
        // 두 칸을 함께 본다 — 한쪽만 밀린 상태는 각각 따로 보면 드러나지 않는다
        assertThat(generationOf("current_generation")).isEqualTo(SECOND_VERSION);
        assertThat(generationOf("prev_generation")).isEqualTo(FIRST_VERSION);
    }

    /**
     * <b>1·2단계 성공 · 3단계 실패.</b> 메타는 이미 밀렸고 옛 버전 청소가 죽는다.
     *
     * <p>메타 시프트만 커밋되면 메타는 3회차를 가리키는데 그 버전의 행은 없다 — 인기순 조회가
     * 통째로 빈 결과가 된다({@code findCurrentVersionViewRows}가 현 버전으로 필터하므로).
     * 실패 이전 두 회차가 값까지 그대로 남아야 그 커서들이 계속 서빙된다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 옛_버전_청소가_실패하면_적재와_메타_시프트가_함께_롤백된다() {
        String before = snapshotOfAllStats();
        doThrow(new IllegalStateException(INJECTED))
                .when(spiedStatsRepository()).deleteVersionsOtherThan(anyLong(), anyLong());

        assertThatThrownBy(() -> batchProcessor.recalculateAll(FAILING_CALCULATED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(INJECTED);

        // 전제 확인: 2단계까지는 실제로 돌았다 — 이 검증이 없으면 "메타 시프트도 롤백됐다"가
        // 애초에 시프트가 호출되지 않은 경우와 구분되지 않는다.
        verify(spiedMetaRepository()).shiftGeneration(FAILING_VERSION);
        assertThat(versionRowCount(FAILING_VERSION)).isZero();
        assertThat(snapshotOfAllStats()).isEqualTo(before);
        // 두 칸을 함께 본다 — 한쪽만 밀린 상태는 각각 따로 보면 드러나지 않는다
        assertThat(generationOf("current_generation")).isEqualTo(SECOND_VERSION);
        assertThat(generationOf("prev_generation")).isEqualTo(FIRST_VERSION);
    }

    /**
     * 스프링 AOP가 스파이를 다시 프록시로 감싸면 주입된 참조는 목이 아니게 되어 {@code doThrow}·
     * {@code verify}가 {@code NotAMockException}으로 죽는다. 감싸지 않았으면 참조를 그대로 돌려준다
     * ({@code PlaceStatsSchedulerLockIT}와 같은 이유).
     */
    private PlaceStatsRepository spiedStatsRepository() {
        return AopTestUtils.getUltimateTargetObject(placeStatsRepository);
    }

    private PlaceStatsMetaRepository spiedMetaRepository() {
        return AopTestUtils.getUltimateTargetObject(placeStatsMetaRepository);
    }

    /** 커밋된 상태를 지운다. 1행 레지스터인 메타는 DELETE가 아니라 UPDATE다(V28의 CHECK). */
    private void resetStats() {
        jdbcTemplate.update("DELETE FROM place_stats");
        jdbcTemplate.update("""
                UPDATE place_stats_meta
                   SET current_generation = NULL, prev_generation = NULL
                 WHERE id = 1
                """);
    }

    private long versionRowCount(long version) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_stats WHERE version = ?", Long.class, version);
    }

    /** 버전 메타 컬럼 하나. BIGINT라 드라이버가 어떤 Number로 주든 받는다 (없으면 null) */
    private Long generationOf(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM place_stats_meta WHERE id = 1", Long.class);
    }

    /**
     * 전체 place_stats를 문자열로 직렬화 — 롤백 전후 비교용. 행 수만 세면 값이 새 회차 것으로
     * 덮인 채 개수만 맞는 상태를 놓치므로 컬럼 전부를 싣는다.
     *
     * <p>{@code group_concat_max_len}을 <b>같은 커넥션</b>에서 올려야 한다 — 세션 변수라
     * {@code JdbcTemplate} 호출을 나누면 다른 커넥션에서 SELECT가 돌아 설정이 안 먹고,
     * GROUP_CONCAT은 한도를 넘으면 <em>조용히 잘린다</em>. 잘린 두 문자열은 서로 같아서 비교가
     * 무의미하게 통과한다. 아래 세그먼트 수 단언이 그 잘림을 잡는다.
     */
    private String snapshotOfAllStats() {
        String snapshot = jdbcTemplate.execute((ConnectionCallback<String>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET SESSION group_concat_max_len = 1000000");
                try (ResultSet rs = st.executeQuery("""
                        SELECT GROUP_CONCAT(
                                   CONCAT_WS('|', place_id, version, town_id, popular_score,
                                             bookmark_count, review_count,
                                             IFNULL(avg_rating, 'NULL'))
                                   ORDER BY version, place_id SEPARATOR ';')
                        FROM place_stats
                        """)) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });

        long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_stats", Long.class);
        assertThat(rowCount).isPositive();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.split(";", -1)).hasSize((int) rowCount);
        return snapshot;
    }
}
