package org.sopt.solply_server.domain.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.admin.auth.repository.AdminAuthCodeRepository;
import org.sopt.solply_server.domain.admin.auth.repository.AdminAuthCodeRepository.ConsumedAuthCode;
import org.sopt.solply_server.domain.admin.auth.repository.AdminOAuthStateRepository;
import org.sopt.solply_server.domain.admin.auth.service.AdminAuthService;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.RefreshTokenService;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.support.AuthMySqlSupport;
import org.sopt.solply_server.support.MutableClock;
import org.sopt.solply_server.support.MutableClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 어드민 임시 데이터의 <b>일회 소비</b>와 <b>만료 경계</b>.
 *
 * <p>Redis에서는 {@code GETDEL} 한 문장이 "읽기 + 일회 소비"였다. MySQL에서 그 원자성을 지탱하는
 * 것은 조건부 UPDATE의 반환 행 수이고, <b>그 판정이 실제로 하나만 통과시키는지는 두 커넥션이
 * 동시에 들어와야 드러난다</b>. 한 스레드로 두 번 부르면 두 번째가 실패하는 것은 당연해서
 * 아무것도 증명하지 않는다.
 *
 * <p>만료 경계를 따로 보는 이유는 조건이 {@code expires_at > now}이기 때문이다. 정각은 이미
 * 만료이고, 그 1ms 차이가 "state가 유효하지 않다"는 같은 메시지로만 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class AdminTempStateConsumeIT extends AuthMySqlSupport {

    private static final String NICKNAME_PREFIX = "어드민IT유저";
    private static final long AWAIT_SECONDS = 20L;

    @Autowired private AdminOAuthStateRepository stateRepository;
    @Autowired private AdminAuthCodeRepository authCodeRepository;
    @Autowired private AdminAuthService adminAuthService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;
    @Autowired private AuthProperties authProperties;
    @Autowired private MockMvc mockMvc;

    private final List<String> createdStates = new java.util.ArrayList<>();

    private ExecutorService executor;
    private long adminUserId;
    private long normalUserId;

    @BeforeEach
    void setUp() {
        clock.setTo(MutableClockConfig.START);
        executor = Executors.newFixedThreadPool(2);
        adminUserId = createUser("어드민", "ADMIN");
        normalUserId = createUser("일반", "USER");
    }

    /**
     * <b>이 IT는 롤백되지 않는다</b>({@code @SpringBootTest} 기본 커밋). 만료된 state를 일부러
     * 심으므로, 지우지 않으면 같은 컨테이너를 쓰는 {@code AuthTokenCleanupIT}가 그것까지 세어
     * "몇 행을 지웠는가" 단언이 흔들린다 — 실제로 한 번 그렇게 깨졌다.
     */
    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (?, ?)",
                adminUserId, normalUserId);
        jdbcTemplate.update("DELETE FROM admin_auth_code WHERE user_id IN (?, ?)",
                adminUserId, normalUserId);
        if (!createdStates.isEmpty()) {
            jdbcTemplate.update("DELETE FROM admin_oauth_state WHERE state IN (%s)"
                            .formatted(String.join(",", createdStates.stream().map(s -> "?").toList())),
                    createdStates.toArray());
            createdStates.clear();
        }
        jdbcTemplate.update("DELETE FROM users WHERE nickname LIKE ?", NICKNAME_PREFIX + "%");
    }

    // === state ===

    /**
     * <b>승자는 하나다.</b> 두 커넥션이 같은 state로 동시에 들어와도 조건부 UPDATE가 1행을 바꾼
     * 쪽만 값을 읽는다. 여기서 둘 다 nonce를 받아 가면 재생 공격이 성립한다 — state가 막으려던
     * 바로 그것이다.
     */
    @Test
    void 같은_state를_동시에_소비하면_한_쪽만_nonce를_받는다() throws Exception {
        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        stateRepository.save(state, nonce, clock.millis() + 60_000);
        createdStates.add(state);

        List<Optional<String>> results = runSimultaneously(
                () -> stateRepository.consume(state, clock.millis()),
                () -> stateRepository.consume(state, clock.millis()));

        assertThat(results).filteredOn(Optional::isPresent)
                .singleElement()
                .satisfies(value -> assertThat(value).contains(nonce));
        assertThat(committedState(state).get("consumed_at")).isNotNull();
    }

    /** {@code expires_at > now}가 조건이다 — 정각은 이미 만료다. */
    @Test
    void state의_만료는_정각에_이미_지난_것으로_본다() {
        String justAlive = saveState(clock.millis() + 1);
        String exactlyExpired = saveState(clock.millis());
        String alreadyExpired = saveState(clock.millis() - 1);

        assertThat(stateRepository.consume(justAlive, clock.millis())).isPresent();
        assertThat(stateRepository.consume(exactlyExpired, clock.millis())).isEmpty();
        assertThat(stateRepository.consume(alreadyExpired, clock.millis())).isEmpty();
    }

    /** 없음·소비됨·만료됨을 구분하지 않는다 — 구분해 알려 주면 어느 state가 존재했는지가 샌다. */
    @Test
    void 없는_state와_이미_소비된_state는_같은_결과다() {
        String state = saveState(clock.millis() + 60_000);
        assertThat(stateRepository.consume(state, clock.millis())).isPresent();

        assertThat(stateRepository.consume(state, clock.millis())).isEmpty();
        assertThat(stateRepository.consume(UUID.randomUUID().toString(), clock.millis())).isEmpty();
    }

    // === authCode ===

    @Test
    void 같은_교환_코드를_동시에_소비하면_한_쪽만_주체를_받는다() throws Exception {
        String code = UUID.randomUUID().toString();
        authCodeRepository.save(code, adminUserId, SocialPlatform.KAKAO, clock.millis() + 60_000);

        List<Optional<ConsumedAuthCode>> results = runSimultaneously(
                () -> authCodeRepository.consume(code, clock.millis()),
                () -> authCodeRepository.consume(code, clock.millis()));

        assertThat(results).filteredOn(Optional::isPresent)
                .singleElement()
                .satisfies(value -> assertThat(value.orElseThrow().userId()).isEqualTo(adminUserId));
    }

    @Test
    void 교환_코드의_만료도_정각에_이미_지난_것으로_본다() {
        String justAlive = saveCode(adminUserId, clock.millis() + 1);
        String exactlyExpired = saveCode(adminUserId, clock.millis());

        assertThat(authCodeRepository.consume(justAlive, clock.millis())).isPresent();
        assertThat(authCodeRepository.consume(exactlyExpired, clock.millis())).isEmpty();
    }

    // === oauth_nonce 쿠키 ===

    /**
     * <b>쿠키 속성 네 가지가 계약이다.</b> state와 짝을 이뤄 콜백을 위조로부터 지키는 값이므로
     * 스크립트가 읽을 수 없어야 하고({@code HttpOnly}), 평문으로 나가면 안 되며({@code Secure}),
     * 다른 사이트가 시작시킨 요청에 실려 가면 안 되고({@code SameSite=Lax}), 콜백 밖의 요청에는
     * 아예 붙지 않아야 한다(경로).
     *
     * <p>구현이 정확해도 테스트가 없으면 그것은 계약이 아니라 우연이다 — 속성 하나가 빠져도
     * 어드민 로그인은 <b>정상 동작하고</b>, 잃는 것은 방어뿐이라 기능 테스트로는 절대 드러나지 않는다.
     * 특히 {@code Secure}는 기본값이 {@code true}인 것 자체가 결정이다(설정을 빠뜨렸을 때
     * 덜 안전한 쪽으로 기울지 않게).
     */
    @Test
    void 인가_URL_요청은_oauth_nonce_쿠키를_네_속성과_함께_내린다() throws Exception {
        String setCookie = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/admin/auth/kakao"))
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(setCookie).isNotNull();
        assertThat(setCookie).startsWith("oauth_nonce=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/api/admin/auth/kakao/callback");
        assertThat(setCookie).contains("Max-Age=" + authProperties.getAdminStateTtl().toSeconds());

        // Secure는 설정값이라 환경마다 갈린다 — 로컬 yml이 http로 돌기 위해 꺼 두면 이 스위트도
        // 꺼진 값을 본다. 그래서 <b>설정을 따라가는지</b>와 <b>코드 기본값이 안전한 쪽인지</b>를
        // 나눠서 본다. 후자가 계약이다: 설정을 빠뜨린 환경이 덜 안전한 쪽으로 기울면 안 된다.
        assertThat(setCookie.contains("Secure"))
                .as("Secure 여부는 solply.auth.oauth-nonce-cookie-secure를 따라야 한다")
                .isEqualTo(authProperties.isOauthNonceCookieSecure());
        assertThat(new AuthProperties().isOauthNonceCookieSecure())
                .as("설정이 없을 때의 기본값은 안전한 쪽이어야 한다")
                .isTrue();

        // 쿠키 값은 저장된 state의 짝이다 — 아무 난수가 아니라 방금 만든 행의 nonce여야 한다
        String nonce = setCookie.substring("oauth_nonce=".length(), setCookie.indexOf(';'));
        assertThat(readCommittedRows(
                "SELECT state FROM admin_oauth_state WHERE nonce = ?", nonce)).hasSize(1);
        jdbcTemplate.update("DELETE FROM admin_oauth_state WHERE nonce = ?", nonce);
    }

    // === 권한 재검사 ===

    /**
     * <b>권한은 발급 트랜잭션 안에서 다시 본다.</b> 코드에 역할을 실어 보내거나 교환 전에 따로
     * 읽어 확인하면, 그 확인과 발급 사이에 권한이 내려간 사용자에게 ADMIN 토큰이 나간다.
     *
     * <p>여기서는 코드가 발급된 <b>뒤</b> 권한을 내려 그 틈을 재현한다.
     */
    @Test
    void 코드_발급_뒤_권한이_내려가면_교환이_막힌다() {
        String code = saveCode(adminUserId, clock.millis() + 60_000);
        jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE id = ?", adminUserId);

        assertThatThrownBy(() -> adminAuthService.exchangeAuthCode(code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_ADMIN_USER);

        // 코드는 소비됐다 — 실패했다고 되돌려 주면 같은 코드로 계속 다시 시도할 수 있다
        assertThat(committedCode(code).get("consumed_at")).isNotNull();
        // 그리고 토큰은 하나도 나가지 않았다
        assertThat(readCommittedRows(
                "SELECT * FROM refresh_token WHERE user_id = ?", adminUserId)).isEmpty();
    }

    @Test
    void ADMIN이_아닌_사용자에게는_어드민_토큰이_나가지_않는다() {
        assertThatThrownBy(() -> refreshTokenService.issueForAdmin(normalUserId, SocialPlatform.KAKAO))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_ADMIN_USER);

        assertThat(readCommittedRows(
                "SELECT * FROM refresh_token WHERE user_id = ?", normalUserId)).isEmpty();
    }

    /** 정상 교환은 ADMIN 역할이 실린 access와 계열 하나를 만든다. */
    @Test
    void 정상_교환은_ADMIN_역할의_토큰을_낸다() {
        String code = saveCode(adminUserId, clock.millis() + 60_000);

        TokenCollectionDto tokens = toCollection(adminAuthService.exchangeAuthCode(code));

        assertThat(jwtTokenProvider.parseAccessToken(tokens.accessToken()).role())
                .isEqualTo(UserRole.ADMIN);
        assertThat(readCommittedRows(
                "SELECT * FROM refresh_token WHERE user_id = ?", adminUserId)).hasSize(1);
        // 같은 코드를 한 번 더 쓰면 막힌다
        assertThatThrownBy(() -> adminAuthService.exchangeAuthCode(code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ADMIN_AUTH_CODE);
    }

    // === helpers ===

    private TokenCollectionDto toCollection(
            org.sopt.solply_server.domain.admin.auth.dto.response.AdminAuthTokenResponse response) {
        return TokenCollectionDto.of(response.accessToken(), response.refreshToken());
    }

    /**
     * 두 호출이 <b>같은 순간에 조건부 UPDATE로 들어가게</b> 한다.
     *
     * <p>한쪽을 먼저 보내고 나중에 보내면 두 번째가 실패하는 것은 당연한 결과라 아무것도 증명하지
     * 않는다. {@link CyclicBarrier}는 두 스레드가 <b>서로를 기다렸다가</b> 함께 풀리므로, 출발
     * 시점이 제3자의 신호가 아니라 둘의 만남으로 정해진다 — 그래서 승패를 가르는 것이 도착 순서가
     * 아니라 엔진의 행 잠금이 된다.
     */
    private <T> List<T> runSimultaneously(Callable<T> first, Callable<T> second) throws Exception {
        CyclicBarrier rendezvous = new CyclicBarrier(2);
        Future<T> a = executor.submit(gated(rendezvous, first));
        Future<T> b = executor.submit(gated(rendezvous, second));
        return List.of(a.get(AWAIT_SECONDS, TimeUnit.SECONDS), b.get(AWAIT_SECONDS, TimeUnit.SECONDS));
    }

    private <T> Callable<T> gated(CyclicBarrier rendezvous, Callable<T> delegate) {
        return () -> {
            rendezvous.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            return delegate.call();
        };
    }

    private String saveState(long expiresAtMillis) {
        String state = UUID.randomUUID().toString();
        stateRepository.save(state, UUID.randomUUID().toString(), expiresAtMillis);
        createdStates.add(state);
        return state;
    }

    private String saveCode(long userId, long expiresAtMillis) {
        String code = UUID.randomUUID().toString();
        authCodeRepository.save(code, userId, SocialPlatform.KAKAO, expiresAtMillis);
        return code;
    }

    private Map<String, Object> committedState(String state) {
        return readCommittedRows("SELECT * FROM admin_oauth_state WHERE state = ?", state).getFirst();
    }

    private Map<String, Object> committedCode(String code) {
        return readCommittedRows("SELECT * FROM admin_auth_code WHERE auth_code = ?", code).getFirst();
    }

    private long createUser(String suffix, String role) {
        String nickname = NICKNAME_PREFIX + suffix;
        jdbcTemplate.update("DELETE FROM users WHERE nickname = ?", nickname);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES (?, ?)", role, nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }
}
