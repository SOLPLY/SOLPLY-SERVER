package org.sopt.solply_server.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.auth.dto.request.SocialLoginRequest;
import org.sopt.solply_server.domain.auth.dto.response.LoginInfoResponse;
import org.sopt.solply_server.domain.auth.dto.response.RefreshResponse;
import org.sopt.solply_server.domain.auth.dto.response.SocialLoginResponse;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.exception.UnauthorizedException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenPayload;
import org.springframework.stereotype.Service;

/**
 * 인증 진입점. <b>트랜잭션을 열지 않는다.</b>
 *
 * <p>예전에는 클래스 레벨 {@code @Transactional}이 있었고, 그 안에서 외부 OAuth HTTP 호출과
 * Redis 쓰기가 함께 일어났다. 저장소가 MySQL로 들어오면서 그 경계가 의미를 갖게 됐다 —
 * 소셜 서버 응답을 기다리는 동안 트랜잭션과(이제는) 사용자 잠금을 들고 있게 되기 때문이다.
 * 그래서 순서를 뒤집었다: <b>외부 호출과 사용자 생성을 먼저 끝내고</b>, 그 다음 짧은 발급
 * 트랜잭션이 잠금을 잡는다.
 *
 * <p>재사용 판정을 401로 바꾸는 것도 여기다. 폐기가 커밋된 <b>뒤</b>여야 하므로 트랜잭션 안에서
 * 던질 수 없다({@code RotationResult} javadoc).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final OAuthServiceProvider oAuthServiceProvider;

    public SocialLoginResponse socialLogin(SocialPlatform socialPlatform, SocialLoginRequest request) {
        OAuthService oAuthService = oAuthServiceProvider.getService(socialPlatform);
        User user = oAuthService.socialLogin(request.token());

        return SocialLoginResponse.of(
                refreshTokenService.issue(user.getId(), socialPlatform),
                user.isNewUser()
        );
    }

    /**
     * 재발급. JWT 검증을 먼저 통과해야 DB 판정으로 내려간다 — <b>JWT가 만료됐으면 재사용 처리를
     * 하지 않는다.</b> 만료는 공격의 신호가 아니라 시간의 결과다.
     */
    public RefreshResponse refreshToken(String refreshToken) {
        RefreshTokenPayload payload = jwtTokenProvider.parseRefreshToken(refreshToken);

        RotationResult result = refreshTokenService.rotate(payload);
        return switch (result) {
            case RotationResult.Rotated rotated -> RefreshResponse.of(rotated.tokens());
            case RotationResult.ReuseDetected ignored ->
                    throw new JwtTokenException(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        };
    }

    /**
     * 계열 로그아웃 — 이 access를 낸 로그인의 refresh만 끊는다.
     *
     * <p><b>"계열만"은 이 호출이 끊는 범위이지, 그 뒤로 다른 기기가 안전하다는 보장이 아니다.</b>
     * 폐기된 토큰이 다시 관찰되면 출처를 묻지 않고 전체 폐기가 정책이므로
     * ({@code RefreshTokenService} 클래스 javadoc), 로그아웃한 계열의 refresh가 한 번이라도 더
     * 도착하면 그 사용자의 모든 계열이 끊긴다. 그래서 클라이언트는 로그아웃을 부르는 순간
     * <b>대기 중인 refresh 요청을 중단하고 재시도 큐를 비우고 토큰을 버려야 한다.</b> 이미
     * 나간 요청은 서버가 회수하지 못한다.
     *
     * <p>인증이 없으면 401이다. 예전에는 {@code /api/auth/**}가 통째로 permitAll이라 익명 호출이
     * 200을 받고 아무 일도 하지 않았다. 시큐리티 설정 대신 여기서 막는 이유는 실패 응답을
     * {@code CustomApiResponse} 모양으로 내보내기 위해서다 — 이 저장소에는
     * {@code AuthenticationEntryPoint}가 없어 필터 단계에서 거절하면 다른 모양의 본문이 나간다.
     */
    public void logout(Long currentUserId, String familyId) {
        if (currentUserId == null || familyId == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED_USER);
        }
        refreshTokenService.revokeFamily(currentUserId, familyId);
    }

    public LoginInfoResponse getSocialLoginInfo(SocialPlatform socialPlatform) {
        if (socialPlatform == null) {
            return null;
        }
        return LoginInfoResponse.of(socialPlatform);
    }
}
