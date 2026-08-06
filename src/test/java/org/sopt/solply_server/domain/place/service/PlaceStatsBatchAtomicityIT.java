package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 * <b>카운트 회차의 두 문장(활성 장소 적재 · 잔행 삭제)이 정말 하나로 죽는가</b>를 본다.
 *
 * <p>{@code PlaceStatsBatchProcessorIT}는 성공 경로만 밟으므로 두 문장이 각자 다른 트랜잭션으로
 * 갈라져도 전부 그린이다. 갈라진 배치가 실제로 만드는 것은 <b>적재는 끝났는데 내려간 장소의 잔행이
 * 남은 구간</b>이고, 삭제만 죽으면 그 잔행이 다음 회차까지 인기순에 그대로 노출된다.
 * 버전 행 시절에는 이 청소를 "옛 버전 통째 삭제"가 겸업했는데(V29), 버전이 사라지면서
 * {@code deleteStaleRows}가 유일한 경로가 됐다 — 그래서 원자성의 무게가 오히려 커졌다.
 *
 * <p><b>점수 회차에는 대응하는 테스트가 없다.</b> 문장이 하나라 원자성을 물을 지점이 없기 때문이다
 * (성공하면 전 행, 실패하면 아무 행도 안 바뀐다). 두 문장으로 늘어나는 순간 여기에 짝을 만들 것.
 *
 * <p><b>모든 테스트가 {@code NOT_SUPPORTED}인 이유 — 반드시 읽을 것.</b> 검증 대상이 "프로세서의
 * 트랜잭션이 롤백되는가"인데, {@code @DataJpaTest}의 테스트 트랜잭션 안에서 부르면 프로세서는
 * <em>그 트랜잭션에 참여</em>할 뿐이라 롤백 주체가 테스트가 되고, 게다가 참여 트랜잭션에서 예외가
 * 나면 스프링이 바깥을 rollback-only로 표시해 이후 단언용 조회조차 성립하지 않는다. 바깥
 * 트랜잭션을 걷어내야만 프로세서가 자기 트랜잭션을 열고 스스로 롤백한다 (운영의 스케줄러 경로와 동일).
 * 그래서 픽스처도 단언도 <b>커밋된 상태</b>를 직접 읽어야 하며, 정리는 {@link #resetStats()}가 맡는다.
 *
 * <p>실패 주입에 스파이를 쓰는 것은 제품 코드에 실패 훅을 내지 않기 위해서다 — 두 문장의 순서와
 * 경계는 {@code PlaceStatsBatchProcessor}가 소유해야 하고, 테스트가 그 안에 갈고리를 박으면
 * 검증 대상이 제품 구조가 아니라 갈고리가 된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, PlaceStatsBatchProcessor.class, PlaceStatsProperties.class})
class PlaceStatsBatchAtomicityIT extends MySqlContainerSupport {

    /** 1회차. 픽스처의 두 회차와 실패할 3회차가 모두 이 시각 기준 상대값이다. */
    private static final LocalDateTime FIRST_CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** 2회차. 운영 간격과 같은 1시간 뒤 — 여기까지가 "정상적으로 쌓인 기존 상태"다. */
    private static final LocalDateTime SECOND_CALCULATED_AT = FIRST_CALCULATED_AT.plusHours(1);

    /** 중간에 죽을 3회차. 이 회차의 흔적이 하나라도 남으면 원자성이 깨진 것이다. */
    private static final LocalDateTime FAILING_CALCULATED_AT = FIRST_CALCULATED_AT.plusHours(2);

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

    /** 두 문장 모두의 주체. 삭제를 실패시키고, 적재가 실제로 돌았는지도 여기서 센다. */
    @SpyBean
    PlaceStatsRepository placeStatsRepository;

    /**
     * 기존 상태를 <b>실제 배치 두 회차로</b> 만든다. 네이티브 INSERT로 흉내 내지 않는 이유는,
     * 롤백 뒤에 남아야 할 것이 "적당한 행들"이 아니라 <b>배치가 만든 그대로의 상태</b>이기 때문이다.
     * 이 픽스처가 곧 스냅샷의 기준값이 된다.
     *
     * <p>점수 회차까지 한 번 돌리는 이유: 롤백 대상이 카운트 회차인데 점수 칸이 0으로만 차 있으면
     * "카운트 회차가 점수를 되돌려 놓았다"는 회귀가 스냅샷 비교에서 드러나지 않는다.
     */
    @BeforeEach
    void 두_회차를_커밋해_기존_상태를_만든다() {
        resetStats();
        batchProcessor.recalculateCounts(FIRST_CALCULATED_AT);
        batchProcessor.recalculateScores(FIRST_CALCULATED_AT);
        batchProcessor.recalculateCounts(SECOND_CALCULATED_AT);
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
     * <b>1단계 성공 · 2단계 실패.</b> 새 회차 값은 이미 적재됐지만 잔행 삭제가 죽는다.
     *
     * <p>두 문장이 갈라져 있으면 적재만 커밋된 상태가 남는다 — 그 자체로는 "값이 최신"이라
     * 겉보기에 멀쩡하지만, 내려간 장소의 잔행이 청소되지 않은 채 다음 회차를 기다린다.
     * 이 사고는 성공 경로 테스트로는 절대 드러나지 않으므로 흔적이 0이어야 한다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 잔행_삭제가_실패하면_적재도_함께_롤백된다() {
        String before = snapshotOfAllStats();
        doThrow(new IllegalStateException(INJECTED))
                .when(spiedStatsRepository()).deleteStaleRows(any(LocalDateTime.class));

        assertThatThrownBy(() -> batchProcessor.recalculateCounts(FAILING_CALCULATED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(INJECTED);

        // 전제 확인: 1단계는 실제로 돌았다. 적재가 아예 안 일어났다면 "롤백됐다"는 단언이 공허하다.
        verify(spiedStatsRepository()).upsertCounts(eq(FAILING_CALCULATED_AT));
        assertThat(rowsCalculatedAt(FAILING_CALCULATED_AT)).isZero();
        assertThat(snapshotOfAllStats()).isEqualTo(before);
    }

    /**
     * <b>실패한 회차는 점수 칸도 건드리지 않는다.</b> 카운트 회차가 롤백되면 그 회차가 만진 것은
     * 전부 되돌아가야 하는데, 점수는 애초에 카운트 회차의 소유가 아니므로 <em>롤백 여부와 무관하게</em>
     * 1회차 채점값이 그대로 살아 있어야 한다.
     *
     * <p>스냅샷 비교(위 테스트)만으로는 "카운트 회차가 점수를 0으로 밀었다가 롤백돼 원복된" 상태와
     * 구분되지 않는다. 그 경우 성공 경로에서는 점수가 조용히 사라지므로, 값 자체를 따로 못 박는다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 롤백_뒤에도_점수_칸은_1회차_채점값을_유지한다() {
        long scoredBefore = scoredRowCount();
        assertThat(scoredBefore).isPositive();   // 픽스처 전제를 값으로 못 박는다

        doThrow(new IllegalStateException(INJECTED))
                .when(spiedStatsRepository()).deleteStaleRows(any(LocalDateTime.class));

        assertThatThrownBy(() -> batchProcessor.recalculateCounts(FAILING_CALCULATED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scoredRowCount()).isEqualTo(scoredBefore);
    }

    /**
     * 스프링 AOP가 스파이를 다시 프록시로 감싸면 주입된 참조는 목이 아니게 되어 {@code doThrow}·
     * {@code verify}가 {@code NotAMockException}으로 죽는다. 감싸지 않았으면 참조를 그대로 돌려준다
     * ({@code PlaceStatsSchedulerLockIT}와 같은 이유).
     */
    private PlaceStatsRepository spiedStatsRepository() {
        return AopTestUtils.getUltimateTargetObject(placeStatsRepository);
    }

    private void resetStats() {
        jdbcTemplate.update("DELETE FROM place_stats");
    }

    private long rowsCalculatedAt(LocalDateTime calculatedAt) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_stats WHERE count_calculated_at = ?",
                Long.class, calculatedAt);
    }

    /** 점수를 받은 행 수. 채점 여부의 진실은 값이 아니라 {@code score_calculated_at}의 non-NULL이다 */
    private long scoredRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_stats WHERE score_calculated_at IS NOT NULL",
                Long.class);
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
                                   CONCAT_WS('|', place_id, town_id, popular_score,
                                             bookmark_count, review_count,
                                             IFNULL(avg_rating, 'NULL'),
                                             count_calculated_at,
                                             IFNULL(score_calculated_at, 'NULL'))
                                   ORDER BY place_id SEPARATOR ';')
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
