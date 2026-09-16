package org.sopt.solply_server.domain.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.admin.auth.controller.AdminAuthController;
import org.sopt.solply_server.domain.admin.auth.dto.response.KakaoAuthUrlResult;
import org.sopt.solply_server.domain.admin.auth.service.AdminAuthService;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code oauth_nonce} 쿠키의 속성. 외부 카카오 호출은 서비스를 목으로 갈아 끼워 들어가지 않는다 —
 * 검증 대상이 응답 헤더 한 줄이라 컨텍스트도 필요 없다.
 */
@ExtendWith(MockitoExtension.class)
class AdminNonceCookieTest {

    private static final String COOKIE_NAME = "oauth_nonce";
    private static final String COOKIE_PATH = "/api/admin/auth/kakao/callback";
    private static final String NONCE = "nonce-0001";

    @Mock private AdminAuthService adminAuthService;

    private AuthProperties authProperties;
    private AdminAuthController controller;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        controller = new AdminAuthController(adminAuthService, authProperties);
        response = new MockHttpServletResponse();
    }

    @Test
    void 발급_쿠키는_HttpOnly_Secure_Lax이고_경로가_콜백으로_좁혀진다() {
        givenAuthUrl();

        controller.kakaoLogin(response);

        String header = setCookieHeader();
        assertThat(nameOf(header)).isEqualTo(COOKIE_NAME);
        assertThat(valueOf(header)).isEqualTo(NONCE);
        assertThat(attributesOf(header))
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=" + COOKIE_PATH);
    }

    /** 쿠키가 state보다 먼저 죽으면 살아 있는 state에 nonce가 없어 콜백이 실패한다. */
    @Test
    void 발급_쿠키의_수명은_state의_수명과_같다() {
        givenAuthUrl();

        controller.kakaoLogin(response);

        assertThat(attributesOf(setCookieHeader()))
                .contains("Max-Age=" + authProperties.getAdminStateTtl().toSeconds());
    }

    /**
     * {@code Secure}는 기본이 켜짐이고 설정으로만 내려간다. 기본값이 뒤집히면 nonce가 평문으로
     * 나가는데, 그 설정을 빠뜨린 환경에서는 아무 증상이 없어 알 방법이 없다.
     */
    @Test
    void Secure_기본값은_켜짐이고_설정으로만_내려간다() {
        assertThat(new AuthProperties().isOauthNonceCookieSecure()).isTrue();
        authProperties.setOauthNonceCookieSecure(false);
        givenAuthUrl();

        controller.kakaoLogin(response);

        List<String> attributes = attributesOf(setCookieHeader());
        assertThat(attributes).doesNotContain("Secure");
        // 나머지 방어는 그대로 남는다 — Secure 하나만 내려가는 것이 로컬 개발의 조건이다
        assertThat(attributes).contains("HttpOnly", "SameSite=Lax", "Path=" + COOKIE_PATH);
    }

    /**
     * 지우는 쿠키는 속성이 발급 쿠키와 <b>같아야</b> 브라우저가 같은 쿠키로 알아본다 —
     * 하나라도 다르면 덮어쓰지 못하고 옛 nonce가 남는다. 그래서 리터럴을 다시 적는 대신
     * 두 헤더를 맞대어 본다(수명과 값만 달라야 한다).
     */
    @Test
    void 삭제_쿠키는_수명과_값만_다르고_속성은_발급_쿠키와_같다() {
        givenAuthUrl();
        controller.kakaoLogin(response);
        String issued = setCookieHeader();

        MockHttpServletResponse callbackResponse = new MockHttpServletResponse();
        given(adminAuthService.processKakaoCallback("code-1", "state-1", NONCE))
                .willReturn("https://admin.solply.app/login/success");
        controller.kakaoCallback("code-1", "state-1", NONCE, callbackResponse);

        String cleared = callbackResponse.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(nameOf(cleared)).isEqualTo(COOKIE_NAME);
        assertThat(valueOf(cleared)).isEmpty();
        assertThat(attributesOf(cleared)).contains("Max-Age=0");
        assertThat(lifetimeIndependentAttributes(cleared))
                .isEqualTo(lifetimeIndependentAttributes(issued));
    }

    private void givenAuthUrl() {
        given(adminAuthService.generateKakaoAuthUrl())
                .willReturn(new KakaoAuthUrlResult("https://kauth.kakao.com/oauth/authorize", NONCE));
    }

    private String setCookieHeader() {
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie 헤더").isNotNull();
        return header;
    }

    private static String nameOf(String setCookie) {
        return firstPair(setCookie)[0];
    }

    private static String valueOf(String setCookie) {
        String[] pair = firstPair(setCookie);
        return pair.length < 2 ? "" : pair[1];
    }

    private static String[] firstPair(String setCookie) {
        return setCookie.split(";", 2)[0].split("=", 2);
    }

    private static List<String> attributesOf(String setCookie) {
        return Arrays.stream(setCookie.split(";")).skip(1).map(String::trim).toList();
    }

    /** 수명 표현(Max-Age·Expires)을 뺀 속성들 — 발급 쿠키와 삭제 쿠키가 같아야 하는 부분이다. */
    private static List<String> lifetimeIndependentAttributes(String setCookie) {
        return attributesOf(setCookie).stream()
                .filter(attribute -> !attribute.startsWith("Max-Age")
                        && !attribute.startsWith("Expires"))
                .toList();
    }
}
