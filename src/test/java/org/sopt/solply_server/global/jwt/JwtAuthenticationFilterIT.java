package org.sopt.solply_server.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.security.PrincipalDetails;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.sopt.solply_server.support.SqlStatementProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 인증 경로의 두 갈래를 문다 — <b>클레임 경로(DB 0회)</b>와 <b>레거시 폴백(DB 1회)</b>.
 *
 * <p>이 변경(2026-08-03)의 값은 "요청마다 유저를 읽지 않는다"이고, 그것은 기능 단언으로는
 * 보이지 않는다 — 인증은 어느 경로로도 성공한다. 그래서 {@link SqlStatementProbe}로
 * <b>나간 문장 수 자체</b>를 단언한다. 폴백으로 되돌아가는 회귀(클레임을 안 읽거나, 읽고도 DB를
 * 타는)는 기능 테스트를 전부 통과하면서 요청당 6문장을 되살리므로, 여기서만 잡힌다.
 *
 * <p>권한 판정도 함께 본다. {@code PrincipalDetails}가 엔티티 없이 세워지면서 authorities의
 * 출처가 {@code user.getRole()}에서 클레임으로 바뀌었는데, {@code SecurityConfig}의
 * {@code hasRole("ADMIN")}은 그 차이를 몰라야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterIT extends MySqlContainerSupport {

    /** 이름은 베이스의 {@code datasource}와 달라야 한다 (static 메서드 숨김 방지) */
    @DynamicPropertySource
    static void filterProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.cron", () -> "-");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlStatementProbe.class::getName);
    }

    /**
     * 인가 판정만 보기 위한 경로. 매핑된 어드민 엔드포인트를 쓰면 컨트롤러가 DB를 타서
     * "문장 0회" 단언과 섞인다. 매핑이 없으면 인가를 통과한 요청은 404가 되고, 막힌 요청은
     * 403이 되므로 <b>판정 결과만</b> 분리해 볼 수 있다.
     */
    private static final String ADMIN_PROBE_PATH = "/api/admin/__auth_probe__";

    private static final String NICKNAME_PREFIX = "필터IT유저";

    @Autowired private JwtAuthenticationFilter jwtAuthenticationFilter;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private long userRoleUserId;
    private long deletedUserId;

    @BeforeEach
    void setUp() {
        userRoleUserId = createUser("일반", "USER");
        deletedUserId = createUser("탈퇴", "USER");
        jdbcTemplate.update(
                "UPDATE users SET is_deleted = true, deleted_at = NOW() WHERE id = ?", deletedUserId);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // === 클레임 경로 ===

    @Test
    void role_클레임이_있으면_DB_조회_없이_인증된다() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(
                userRoleUserId, SocialPlatform.KAKAO, UserRole.USER);
        SqlStatementProbe.clear();

        PrincipalDetails principal = authenticate(token);

        assertThat(principal.getUserId()).isEqualTo(userRoleUserId);
        assertThat(authorities(principal)).containsExactly("ROLE_USER");
        assertThat(principal.getPlatform()).isEqualTo(SocialPlatform.KAKAO);
        // 이 단언이 이 변경의 전부다 — 인증에 문장이 하나도 들지 않는다
        assertThat(SqlStatementProbe.sqls()).isEmpty();
    }

    /**
     * <b>수용한 대가를 명시한다.</b> 클레임 경로는 DB를 보지 않으므로 그 유저가 지금 존재하는지
     * (탈퇴했는지) 알 수 없고, 남은 토큰 수명 동안 인증이 성립한다. 이것이 "즉시 차단은 토큰 수명
     * 관리의 책임"이라는 결정의 관측 가능한 형태다 — 몰랐던 부작용으로 나중에 발견되지 않도록
     * 테스트로 고정한다. 이 동작을 바꾸려면 여기를 먼저 고쳐야 한다.
     */
    @Test
    void 클레임_경로는_유저의_현재_상태를_보지_않는다() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(
                deletedUserId, SocialPlatform.KAKAO, UserRole.USER);
        SqlStatementProbe.clear();

        assertThat(authenticate(token).getUserId()).isEqualTo(deletedUserId);
        assertThat(SqlStatementProbe.sqls()).isEmpty();
    }

    // === 권한 판정 ===

    @Test
    void ADMIN_클레임_토큰은_어드민_경로의_인가를_통과한다() throws Exception {
        String adminToken = jwtTokenProvider.generateAccessToken(
                userRoleUserId, SocialPlatform.KAKAO, UserRole.ADMIN);
        SqlStatementProbe.clear();

        int status = mockMvc.perform(get(ADMIN_PROBE_PATH)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();

        // 매핑이 없으니 404다 — 중요한 것은 403이 아니라는 사실이다
        assertThat(status).isNotEqualTo(403);
        // users 행의 role은 USER인데 토큰 클레임이 ADMIN이다 → 판정 근거가 클레임임을 증명한다
        assertThat(SqlStatementProbe.sqls()).isEmpty();
    }

    @Test
    void USER_클레임_토큰은_어드민_경로에서_거부된다() throws Exception {
        String userToken = jwtTokenProvider.generateAccessToken(
                userRoleUserId, SocialPlatform.KAKAO, UserRole.USER);

        int status = mockMvc.perform(get(ADMIN_PROBE_PATH)
                        .header("Authorization", "Bearer " + userToken))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    // === 레거시 폴백 ===

    /**
     * role 클레임이 없는 토큰은 이 기능 이전에 발급된 것이다. 동작은 예전과 같아야 하고
     * (권한은 DB의 role), 문장 1개는 그 조회다 — 트랜잭션 없이 나가는 것까지 함께 확인한다
     * ({@code UserRepository#findForAuthentication}).
     */
    @Test
    void role_클레임이_없는_옛_토큰은_DB_조회로_폴백한다() throws Exception {
        String legacy = legacyAccessToken(userRoleUserId);
        SqlStatementProbe.clear();

        PrincipalDetails principal = authenticate(legacy);

        assertThat(principal.getUserId()).isEqualTo(userRoleUserId);
        assertThat(authorities(principal)).containsExactly("ROLE_USER");
        assertThat(SqlStatementProbe.sqls()).hasSize(1);
        assertThat(SqlStatementProbe.sqls().get(0)).containsIgnoringCase("from users");
        assertThat(SqlStatementProbe.txActive()).containsExactly(false);
    }

    /** 폴백 경로의 의미는 변경 전과 동일하다 — 탈퇴 유저는 {@code NOT_FOUND_USER}로 막힌다 */
    @Test
    void 옛_토큰의_탈퇴_유저는_폴백_경로에서_거부된다() {
        String legacy = legacyAccessToken(deletedUserId);

        assertThatThrownBy(() -> authenticate(legacy))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND_USER);
    }

    // === helpers ===

    /**
     * 필터를 직접 통과시켜 {@code SecurityContext}에 세워진 주체를 돌려준다.
     * MVC를 거치지 않으므로 여기서 관측되는 문장은 <b>인증이 낸 것뿐</b>이다.
     */
    private PrincipalDetails authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/places");
        request.addHeader("Authorization", "Bearer " + token);
        jwtAuthenticationFilter.doFilter(
                request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        return (PrincipalDetails) authentication.getPrincipal();
    }

    private Iterable<String> authorities(PrincipalDetails principal) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /**
     * role 클레임 도입 <b>이전</b> 형태의 access 토큰. 프로바이더로는 만들 수 없으므로
     * (이제 항상 role을 싣는다) 같은 서명 키로 직접 만든다 — 실제로 남아 있는 토큰의 재현이다.
     */
    private String legacyAccessToken(long userId) {
        Key key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getAccessSecretKey()));
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("type", "access")
                .claim("platform", SocialPlatform.KAKAO.name())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + 60_000))
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    private long createUser(String suffix, String role) {
        String nickname = NICKNAME_PREFIX + suffix;
        jdbcTemplate.update("DELETE FROM users WHERE nickname = ?", nickname);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES (?, ?)", role, nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /** {@code @SpringBootTest}는 기본 커밋이라 만든 유저가 남는다 ({@code PlaceListFlowIT}과 같은 사정) */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM users WHERE nickname LIKE '" + NICKNAME_PREFIX + "%'");
        }
    }
}
