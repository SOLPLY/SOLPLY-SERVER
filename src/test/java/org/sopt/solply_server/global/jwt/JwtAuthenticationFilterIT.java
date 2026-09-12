package org.sopt.solply_server.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.RefreshTokenService;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.security.PrincipalDetails;
import org.sopt.solply_server.support.AuthMySqlSupport;
import org.sopt.solply_server.support.ConnectionCountingDataSourceConfig;
import org.sopt.solply_server.support.SqlStatementProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
 * 인증 경로의 계약 하나 — <b>저장소를 전혀 읽지 않는다.</b>
 *
 * <p>이 IT는 2026-09-12에 절반이 사라졌다. 레거시 폴백(role 클레임 없는 옛 토큰을 DB로 메우는
 * 분기)이 제거되면서 그것을 물던 테스트 둘이 검증할 대상을 잃었다 — 그 토큰들은 새 필수 클레임이
 * 없어 <b>파싱 단계에서</b> 거절되므로, 폴백이 받아 줄 대상 자체가 없다. 그래서 "폴백이 DB를
 * 1회 읽는다"는 단언이 "옛 토큰은 인증 단계를 통과하지 못한다"로 바뀌었다.
 *
 * <p><b>관찰자도 함께 바꿨다.</b> {@link SqlStatementProbe}는 하이버네이트가 만드는 문장만 본다.
 * 인증이 {@code JdbcTemplate}으로 내려가는 회귀는 그 관찰자에게 보이지 않으므로,
 * {@link ConnectionCountingDataSourceConfig}가 <b>커넥션 획득</b>을 함께 센다 — 어떤 계층이
 * 무엇으로 질의하든 DB에 닿으려면 그 지점을 지난다. Redis 쪽은 커넥션 팩토리를 스파이로 감싸
 * 같은 질문을 던진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConnectionCountingDataSourceConfig.class)
class JwtAuthenticationFilterIT extends AuthMySqlSupport {

    /** 이름은 베이스의 {@code datasource}·{@code authSuiteProps}와 달라야 한다 (static 메서드 숨김 방지) */
    @DynamicPropertySource
    static void filterProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlStatementProbe.class::getName);
    }

    /**
     * 인가 판정만 보기 위한 경로. 매핑된 어드민 엔드포인트를 쓰면 컨트롤러가 DB를 타서
     * "저장소 0회" 단언과 섞인다. 매핑이 없으면 인가를 통과한 요청은 404가 되고, 막힌 요청은
     * 403이 되므로 <b>판정 결과만</b> 분리해 볼 수 있다.
     */
    private static final String ADMIN_PROBE_PATH = "/api/admin/__auth_probe__";

    private static final String NICKNAME_PREFIX = "필터IT유저";

    @Autowired private JwtAuthenticationFilter jwtAuthenticationFilter;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    /** 인증이 Redis에 닿는지 묻는 지점. 추천 캐시는 남아 있으므로 팩토리 자체는 실물이다. */
    @SpyBean private RedisConnectionFactory redisConnectionFactory;

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

    /** 계약 테스트가 실제 계열을 만들므로 유저 행을 다시 만들기 전에 refresh 쪽을 먼저 지운다. */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (?, ?)",
                userRoleUserId, deletedUserId);
    }

    // === 저장소를 읽지 않는다 ===

    /**
     * <b>이 단언이 이 설계의 값 전부다.</b> 인증에 SQL도, 커넥션도, Redis도 들지 않는다.
     * 폴백으로 되돌아가는 회귀(클레임을 안 읽거나, 읽고도 저장소를 타는)는 기능 테스트를 전부
     * 통과하면서 요청마다 저장소 왕복을 되살리므로, 여기서만 잡힌다.
     */
    @Test
    void 인증은_DB도_Redis도_읽지_않는다() throws Exception {
        String token = accessToken(userRoleUserId, UserRole.USER);
        startObserving();

        PrincipalDetails principal = authenticate(token);

        assertThat(principal.getUserId()).isEqualTo(userRoleUserId);
        assertThat(authorities(principal)).containsExactly("ROLE_USER");
        assertThat(principal.getPlatform()).isEqualTo(SocialPlatform.KAKAO);
        assertThat(SqlStatementProbe.sqls()).isEmpty();
        assertThat(ConnectionCountingDataSourceConfig.acquiredConnections()).isZero();
        verify(redisConnectionFactory, never()).getConnection();
    }

    /** 계열 ID도 클레임에서 온다 — 로그아웃이 끊을 대상을 저장소에 묻지 않는다. */
    @Test
    void 계열_ID는_클레임에서_주체로_옮겨진다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(
                userRoleUserId, SocialPlatform.KAKAO, UserRole.USER, "family-abc");

        assertThat(authenticate(token).getFamilyId()).isEqualTo("family-abc");
    }

    /**
     * <b>수용한 대가를 명시한다.</b> 인증 경로는 저장소를 보지 않으므로 그 유저가 지금 존재하는지
     * (탈퇴했는지) 알 수 없고, 남은 토큰 수명 동안 인증이 성립한다. 이것이 "즉시 차단은 도메인
     * 로직의 책임"이라는 결정의 관측 가능한 형태다 — 몰랐던 부작용으로 나중에 발견되지 않도록
     * 테스트로 고정한다. 이 동작을 바꾸려면 여기를 먼저 고쳐야 한다.
     */
    @Test
    void 인증_단계는_유저의_현재_상태를_보지_않는다() throws Exception {
        String token = accessToken(deletedUserId, UserRole.USER);
        startObserving();

        assertThat(authenticate(token).getUserId()).isEqualTo(deletedUserId);
        assertThat(SqlStatementProbe.sqls()).isEmpty();
        assertThat(ConnectionCountingDataSourceConfig.acquiredConnections()).isZero();
    }

    // === 권한 판정 ===

    @Test
    void ADMIN_클레임_토큰은_어드민_경로의_인가를_통과한다() throws Exception {
        String adminToken = accessToken(userRoleUserId, UserRole.ADMIN);
        startObserving();

        int status = mockMvc.perform(get(ADMIN_PROBE_PATH)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();

        // 매핑이 없는 경로를 고른 이유가 이 404다. 500도 "403이 아니다"를 만족하므로
        // 정확한 값으로 받는다 — 인가를 통과했다는 말이 되려면 404여야 한다
        assertThat(status).isEqualTo(404);
        // users 행의 role은 USER인데 토큰 클레임이 ADMIN이다 → 판정 근거가 클레임임을 증명한다
        assertThat(SqlStatementProbe.sqls()).isEmpty();
        assertThat(ConnectionCountingDataSourceConfig.acquiredConnections()).isZero();
    }

    @Test
    void USER_클레임_토큰은_어드민_경로에서_거부된다() throws Exception {
        String userToken = accessToken(userRoleUserId, UserRole.USER);

        int status = mockMvc.perform(get(ADMIN_PROBE_PATH)
                        .header("Authorization", "Bearer " + userToken))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    void 토큰_없는_요청은_어드민_경로에서_막힌다() throws Exception {
        int status = mockMvc.perform(get(ADMIN_PROBE_PATH)).andReturn().getResponse().getStatus();

        assertThat(status).isIn(401, 403);
    }

    // === 옛 토큰 ===

    /**
     * <b>전환의 관측 가능한 형태.</b> 새 필수 클레임({@code iss}·{@code aud}·{@code fid}·{@code ver})이
     * 없는 옛 access는 폴백으로 구제되지 않고 파싱에서 거절된다. "무중단 호환"이 아니라
     * 전원 재로그인을 동반하는 전환이라는 사실이 이 테스트다.
     */
    @Test
    void 새_필수_클레임이_없는_옛_토큰은_거절된다() {
        String legacy = legacyAccessToken(userRoleUserId);

        assertThatThrownBy(() -> authenticate(legacy)).isInstanceOf(JwtTokenException.class);
    }

    // === 클라이언트 계약 ===

    /**
     * 인증 없는 로그아웃은 <b>401</b>이고, 본문은 {@code CustomApiResponse} 모양이어야 한다.
     * 예전에는 {@code /api/auth/**}가 통째로 permitAll이라 익명 호출이 200을 받고 아무 일도
     * 하지 않았다. 시큐리티 설정이 아니라 서비스에서 막는 이유가 이 본문 모양이다 — 이 저장소에는
     * {@code AuthenticationEntryPoint}가 없어 필터 단계에서 거절하면 다른 모양이 나간다.
     */
    @Test
    void 인증_없는_로그아웃은_401과_AUTH_001을_돌려준다() throws Exception {
        mockMvc.perform(delete("/api/auth/logout"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("AUTH-001"));
    }

    /** 만료된 access는 {@code AUTH-004}다 — 클라이언트가 할 일이 "재발급"이라는 신호다. */
    @Test
    void 만료된_access로_보낸_요청은_AUTH_004를_돌려준다() throws Exception {
        String expired = expiredAccessToken(userRoleUserId);

        mockMvc.perform(delete("/api/auth/logout").header("Authorization", "Bearer " + expired))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("AUTH-004"));
    }

    /**
     * <b>refresh 입력은 {@code Refresh-Token} 헤더다</b> — Bearer가 아니고 본문도 아니다.
     * 응답은 {@code accessToken}·{@code refreshToken} 두 필드이고, 이 모양이 모바일 클라이언트가
     * 이미 쓰고 있는 계약이라 이번 전환에서 바뀌지 않았다.
     */
    @Test
    void refresh는_Refresh_Token_헤더로_받고_기존_DTO를_돌려준다() throws Exception {
        String refreshToken = refreshTokenService
                .issue(userRoleUserId, SocialPlatform.KAKAO).refreshToken();

        mockMvc.perform(post("/api/auth/refresh").header("Refresh-Token", refreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        // Bearer 접두사를 붙이면 우리 토큰이 아니다 — 헤더 값의 모양도 계약이다
        mockMvc.perform(post("/api/auth/refresh").header("Refresh-Token", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    /** 유효한 access로 부른 로그아웃은 200이고, 그 access를 낸 계열만 끊는다. */
    @Test
    void 로그아웃은_그_access를_낸_계열만_끊고_200을_돌려준다() throws Exception {
        TokenCollectionDto phone = refreshTokenService.issue(userRoleUserId, SocialPlatform.KAKAO);
        TokenCollectionDto tablet = refreshTokenService.issue(userRoleUserId, SocialPlatform.KAKAO);

        mockMvc.perform(delete("/api/auth/logout")
                        .header("Authorization", "Bearer " + phone.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(revokedAt(phone.refreshToken())).isNotNull();
        assertThat(revokedAt(tablet.refreshToken())).isNull();

        // 같은 access로 한 번 더 불러도 200이다 — 반복 호출하는 클라이언트가 정상이다
        mockMvc.perform(delete("/api/auth/logout")
                        .header("Authorization", "Bearer " + phone.accessToken()))
                .andExpect(status().isOk());
    }

    /**
     * <b>헤더를 빠뜨린 것은 클라이언트의 입력 실수이므로 400이다.</b>
     *
     * <p>이 테스트가 처음 돌았을 때는 500이었다. {@code MissingRequestHeaderException}에 핸들러가
     * 없어 포괄 {@code @ExceptionHandler(Exception.class)}로 떨어졌고, 그러면 클라이언트가 자기
     * 요청을 고칠 수 있는 실수를 "서버가 깨졌다"로 읽는다. 보고 → 구현자가
     * {@code GlobalExceptionHandler#handleMissingRequestHeaderException}을 추가 →
     * 여기서 뒤집었다. 그 왕복을 남겨 두는 이유는, 같은 구멍이 다른 필수 헤더에서 다시 생겼을 때
     * 이 테스트가 무엇을 지키는 자리인지 읽히게 하기 위해서다.
     */
    @Test
    void refresh_헤더가_아예_없으면_400이다() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // === helpers ===

    /**
     * 관찰자를 비우기 전에 <b>관찰자가 살아 있는지 먼저 확인한다.</b> 배선이 빠지면 "0회"가 언제나
     * 참이 되어 단언이 조용히 공허해진다 — 이 스위트에서 가장 값어치 있는 단언이 하필 0을 세는
     * 것이라 자기 점검이 필요하다.
     */
    private void startObserving() {
        ConnectionCountingDataSourceConfig.clear();
        SqlStatementProbe.clear();
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(ConnectionCountingDataSourceConfig.acquiredConnections())
                .as("커넥션 관찰자가 배선되지 않았다면 아래의 0회 단언은 아무것도 증명하지 않는다")
                .isPositive();

        SqlStatementProbe.clear();
        ConnectionCountingDataSourceConfig.clear();
        org.mockito.Mockito.clearInvocations(redisConnectionFactory);
    }

    private String accessToken(long userId, UserRole role) {
        return jwtTokenProvider.createAccessToken(
                userId, SocialPlatform.KAKAO, role, java.util.UUID.randomUUID().toString());
    }

    /**
     * 필터를 직접 통과시켜 {@code SecurityContext}에 세워진 주체를 돌려준다.
     * MVC를 거치지 않으므로 여기서 관측되는 문장은 <b>인증이 낸 것뿐</b>이다.
     */
    private PrincipalDetails authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/places");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        jwtAuthenticationFilter.doFilter(request, response, new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // 필터가 예외를 resolver로 넘겼다는 뜻이다 — 호출자가 거절을 단언할 수 있게 다시 던진다
            throw (JwtTokenException) jwtTokenProviderFailure(token);
        }
        return (PrincipalDetails) authentication.getPrincipal();
    }

    /** 거절 사유를 값으로 만들기 위해 같은 토큰을 프로바이더에 다시 통과시킨다. */
    private RuntimeException jwtTokenProviderFailure(String token) {
        try {
            jwtTokenProvider.parseAccessToken(token);
            return new IllegalStateException("토큰이 거절되지 않았는데 인증도 세워지지 않았다");
        } catch (JwtTokenException e) {
            return e;
        }
    }

    private Iterable<String> authorities(PrincipalDetails principal) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /**
     * 새 필수 클레임 도입 <b>이전</b> 형태의 access 토큰. 프로바이더로는 만들 수 없으므로
     * 같은 서명 키로 직접 만든다 — 실제로 남아 있던 토큰의 재현이다.
     */
    private String legacyAccessToken(long userId) {
        java.security.Key key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                io.jsonwebtoken.io.Decoders.BASE64.decode(accessSecret()));
        java.util.Date now = new java.util.Date();
        return io.jsonwebtoken.Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("type", "access")
                .claim("platform", SocialPlatform.KAKAO.name())
                .claim("role", UserRole.USER.name())
                .setIssuedAt(now)
                .setExpiration(new java.util.Date(now.getTime() + 60_000))
                .signWith(key, io.jsonwebtoken.SignatureAlgorithm.HS512)
                .compact();
    }

    /** 이미 만료된 access. 클레임 집합은 지금 판 그대로이고 시각만 과거다. */
    private String expiredAccessToken(long userId) {
        java.security.Key key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                io.jsonwebtoken.io.Decoders.BASE64.decode(accessSecret()));
        long now = System.currentTimeMillis() / 1000L;
        return io.jsonwebtoken.Jwts.builder()
                .setIssuer(jwtProperties.getIssuer())
                .setAudience(jwtProperties.getAudience())
                .setSubject(String.valueOf(userId))
                .claim("type", "access")
                .claim("platform", SocialPlatform.KAKAO.name())
                .claim("role", UserRole.USER.name())
                .claim("fid", "family-expired")
                .claim("ver", JwtTokenProvider.TOKEN_FORMAT_VERSION)
                .setIssuedAt(new java.util.Date((now - 7_200) * 1000L))
                .setExpiration(new java.util.Date((now - 3_600) * 1000L))
                .signWith(key, io.jsonwebtoken.SignatureAlgorithm.HS512)
                .compact();
    }

    private String accessSecret() {
        return jwtProperties.getAccessSecretKey();
    }

    /** 커밋된 refresh 행의 폐기 시각. 로그아웃의 범위를 값으로 본다. */
    private Object revokedAt(String refreshToken) {
        String jwtId = jwtTokenProvider.parseRefreshToken(refreshToken).jwtId();
        return readCommittedRows("SELECT revoked_at FROM refresh_token WHERE jwt_id = ?", jwtId)
                .getFirst().get("revoked_at");
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
