package org.sopt.solply_server.domain.admin.auth.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.auth.dto.response.AdminAuthTokenResponse;
import org.sopt.solply_server.domain.admin.auth.dto.response.KakaoAuthUrlResult;
import org.sopt.solply_server.domain.admin.auth.repository.AdminAuthCodeRepository;
import org.sopt.solply_server.domain.admin.auth.repository.AdminAuthCodeRepository.ConsumedAuthCode;
import org.sopt.solply_server.domain.admin.auth.repository.AdminOAuthStateRepository;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.RefreshTokenService;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.config.KakaoOAuthProperties;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.feign.oauth.kakao.KakaoAuthClient;
import org.sopt.solply_server.global.feign.oauth.kakao.KakaoServerClient;
import org.sopt.solply_server.global.feign.oauth.kakao.dto.KakaoSocialUserProfile;
import org.sopt.solply_server.global.feign.oauth.kakao.dto.KakaoTokenResponse;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 어드민 카카오 로그인. state/nonce와 일회용 authCode가 Redis에서 MySQL로 내려왔다.
 *
 * <p>흐름은 바뀌지 않았다 — 인가 URL 발급 → 콜백에서 state·nonce 검증 → 카카오 토큰·프로필 →
 * ADMIN 확인 → 일회용 코드 발급 → 코드 교환. 바뀐 것은 <b>일회 소비를 무엇이 보장하는가</b>다.
 * {@code GETDEL} 대신 조건부 UPDATE가 승자를 하나로 정한다(각 저장소 javadoc).
 *
 * <p><b>이 클래스는 트랜잭션을 열지 않는다.</b> 카카오 HTTP 호출이 두 번 들어 있어, 열면 그
 * 왕복이 통째로 트랜잭션 안에 들어온다. 소비와 발급은 각 저장소·서비스가 자기 경계를 갖는다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private static final String GRANT_TYPE = "authorization_code";

    private final KakaoAuthClient kakaoAuthClient;
    private final KakaoServerClient kakaoServerClient;
    private final SocialUserInfoRepository socialUserInfoRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final AdminAuthCodeRepository adminAuthCodeRepository;
    private final AdminOAuthStateRepository adminOAuthStateRepository;
    private final KakaoOAuthProperties kakaoOAuthProperties;
    private final AuthProperties authProperties;
    private final Clock clock;

    @Value("${admin.redirect-uri}")
    private String adminRedirectUri;

    public KakaoAuthUrlResult generateKakaoAuthUrl() {
        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        adminOAuthStateRepository.save(state, nonce,
                clock.millis() + authProperties.getAdminStateTtl().toMillis());

        String url = UriComponentsBuilder.fromHttpUrl(kakaoOAuthProperties.getAuthorizationUrl())
                .queryParam("client_id", kakaoOAuthProperties.getClientId())
                .queryParam("redirect_uri", kakaoOAuthProperties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build()
                .toUriString();

        return new KakaoAuthUrlResult(url, nonce);
    }

    /**
     * state는 <b>검증 성공 여부와 무관하게 소비된다.</b> nonce가 틀렸다고 state를 남겨 두면 같은
     * state로 계속 다시 시도할 수 있고, 그것이 곧 state가 막으려던 재생 공격이다.
     */
    public String processKakaoCallback(String code, String state, String nonce) {
        Optional<String> storedNonce = adminOAuthStateRepository.consume(state, clock.millis());
        if (storedNonce.isEmpty() || !storedNonce.get().equals(nonce)) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE);
        }

        KakaoTokenResponse tokenResponse = kakaoAuthClient.getToken(
                GRANT_TYPE,
                kakaoOAuthProperties.getClientId(),
                kakaoOAuthProperties.getRedirectUri(),
                code,
                kakaoOAuthProperties.getClientSecret()
        );

        KakaoSocialUserProfile profile = kakaoServerClient.getUserInformation(
                "Bearer " + tokenResponse.accessToken()
        );

        User user = findAdminUser(profile);

        String authCode = UUID.randomUUID().toString();
        adminAuthCodeRepository.save(authCode, user.getId(), SocialPlatform.KAKAO,
                clock.millis() + authProperties.getAdminAuthCodeTtl().toMillis());

        return UriComponentsBuilder.fromHttpUrl(adminRedirectUri)
                .queryParam("authCode", authCode)
                .build()
                .toUriString();
    }

    /**
     * 코드 교환. <b>권한 재검사는 발급 트랜잭션 안에서 한다</b>
     * ({@code RefreshTokenService#issueForAdmin}). 여기서 먼저 읽어 확인하면 확인과 발급 사이가
     * 벌어져, 그 틈에 권한이 내려간 사용자에게 ADMIN 토큰이 나간다.
     */
    public AdminAuthTokenResponse exchangeAuthCode(String authCode) {
        ConsumedAuthCode consumed = adminAuthCodeRepository.consume(authCode, clock.millis())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ADMIN_AUTH_CODE));

        TokenCollectionDto tokens =
                refreshTokenService.issueForAdmin(consumed.userId(), consumed.platform());

        return new AdminAuthTokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    private String buildSocialCode(SocialPlatform platform, String socialId) {
        return platform.name() + "_" + socialId;
    }

    private User findAdminUser(KakaoSocialUserProfile profile) {
        String socialCode = buildSocialCode(SocialPlatform.KAKAO, String.valueOf(profile.getId()));

        String email = profile.getEmail();
        User user = socialUserInfoRepository.findAnyUserIdBySocialCode(socialCode)
                .flatMap(userRepository::findById)
                .or(() -> email != null && !email.isBlank()
                        ? userRepository.findAnyByEmail(email)
                        : Optional.empty())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.NOT_ADMIN_USER);
        }

        return user;
    }
}
