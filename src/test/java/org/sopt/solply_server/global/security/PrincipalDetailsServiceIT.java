package org.sopt.solply_server.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.sopt.solply_server.support.SqlStatementProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 인증 주체 로딩의 두 계약을 문다 — <b>소프트 삭제 필터</b>와 <b>무트랜잭션</b>.
 *
 * <p><b>왜 통합이어야 하는가.</b> 이 변경(2026-08-03)은 {@code findById} 대신
 * {@code findForAuthentication}을 쓰면서 트랜잭션 전파를 {@code SUPPORTS}로 낮춘 것인데,
 * 그 두 가지는 목으로 검증할 수 없다:
 * <ul>
 *   <li>{@code @Where(is_deleted = false)}는 하이버네이트가 SQL을 만들 때 붙는다. 조회를
 *       네이티브로 내리거나({@code findAnyById}처럼) 필터를 우회하면 <b>탈퇴 유저의 토큰이
 *       인증을 통과</b>하는데, 목 레포지토리는 그 차이를 재현하지 못한다.</li>
 *   <li>전파 설정이 실제로 먹었는지는 실행 중 트랜잭션 유무로만 알 수 있다. 아래
 *       {@link SqlStatementProbe}가 SQL 준비 시점의 {@code isActualTransactionActive()}를 기록해
 *       그것을 값으로 만든다 — 요청당 제어 문장 5개(SET autocommit ×2 · SET SESSION
 *       TRANSACTION READ ONLY/WRITE · COMMIT)가 사라졌다는 주장의 근거가 이 단언이다.</li>
 * </ul>
 *
 * <p>테스트 트랜잭션이 있으면 {@code SUPPORTS}가 그것에 <em>참여</em>해 무트랜잭션 단언이
 * 무의미해진다. 그래서 {@code @DataJpaTest}가 아니라 {@code @SpringBootTest}이고
 * {@code @Transactional}을 붙이지 않는다 — 대신 만든 행을 {@code @AfterAll}에서 직접 지운다.
 */
@SpringBootTest
class PrincipalDetailsServiceIT extends MySqlContainerSupport {

    /**
     * 베이스의 {@code datasource}와 이름이 겹치면 상위 메서드를 숨겨 데이터소스 설정이 사라진다.
     *
     * <p>{@code place-stats.cron}을 끄는 이유는 {@code PlaceListFlowIT}과 같다 — 실제 앱을 띄우므로
     * 배치 {@code @Scheduled}가 등록되고, 스위트 도중 돌면 다른 IT가 의존하는 place_stats를 덮는다.
     */
    @DynamicPropertySource
    static void authProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.cron", () -> "-");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlStatementProbe.class::getName);
    }

    private static final String NICKNAME_PREFIX = "인증IT유저";

    @Autowired
    private PrincipalDetailsService principalDetailsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long aliveUserId;
    private long deletedUserId;

    @BeforeEach
    void setUp() {
        aliveUserId = createUser("정상");
        deletedUserId = createUser("탈퇴");
        jdbcTemplate.update(
                "UPDATE users SET is_deleted = true, deleted_at = NOW() WHERE id = ?", deletedUserId);
        SqlStatementProbe.clear();
    }

    @Test
    void 활성_유저는_인증_주체로_로딩된다() {
        PrincipalDetails principal = principalDetailsService.loadUserWithPlatform(aliveUserId, null);

        assertThat(principal.getUserId()).isEqualTo(aliveUserId);
    }

    /**
     * <b>불변 조건.</b> 소프트 삭제된 유저는 행이 남아 있어도 조회되지 않아야 하고, 그 결과는
     * 변경 전과 동일한 {@code NOT_FOUND_USER}여야 한다. 조회를 네이티브로 바꾸거나
     * {@code @Where}를 잃으면 여기서 예외 대신 정상 주체가 나온다.
     */
    @Test
    void 소프트_삭제된_유저는_NOT_FOUND_USER다() {
        // 행 자체는 살아 있다 — 필터가 거른 것이지 데이터가 사라진 것이 아니다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, deletedUserId))
                .isEqualTo(1);

        assertThatThrownBy(() -> principalDetailsService.loadUserWithPlatform(deletedUserId, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND_USER);
    }

    /** {@code UserDetailsService} 진입점도 같은 조회를 쓴다 — 두 입구의 의미가 갈리면 안 된다 */
    @Test
    void UserDetailsService_진입점도_소프트_삭제를_거른다() {
        assertThat(((PrincipalDetails) principalDetailsService
                .loadUserByUsername(String.valueOf(aliveUserId))).getUserId())
                .isEqualTo(aliveUserId);

        assertThatThrownBy(() -> principalDetailsService
                .loadUserByUsername(String.valueOf(deletedUserId)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * <b>이 변경의 값을 재는 단언.</b> 인증 조회는 SELECT 1문장이고, 그 문장이 나갈 때
     * 트랜잭션이 없어야 한다. {@code findById}로 되돌리거나 전파를 지우면
     * {@code isActualTransactionActive()}가 true가 되어 즉시 깨진다.
     */
    @Test
    void 인증_조회는_트랜잭션을_열지_않는다() {
        SqlStatementProbe.clear();

        principalDetailsService.loadUserWithPlatform(aliveUserId, null);

        assertThat(SqlStatementProbe.sqls()).hasSize(1);
        assertThat(SqlStatementProbe.sqls().get(0)).containsIgnoringCase("from users");
        // 소프트 삭제 필터가 SQL에 실제로 붙었다는 증거도 여기서 함께 남는다
        assertThat(SqlStatementProbe.sqls().get(0)).containsIgnoringCase("is_deleted");
        assertThat(SqlStatementProbe.txActive()).containsExactly(false);
    }

    private long createUser(String suffix) {
        String nickname = NICKNAME_PREFIX + suffix;
        jdbcTemplate.update("DELETE FROM users WHERE nickname = ?", nickname);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /**
     * {@code @SpringBootTest}는 기본 커밋이라 만든 유저가 남는다. {@code @AfterAll}은 static이어서
     * {@code JdbcTemplate}에 닿을 수 없으므로 커넥션을 직접 연다({@code PlaceListFlowIT}과 같은 이유).
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM users WHERE nickname LIKE '" + NICKNAME_PREFIX + "%'");
        }
    }
}
