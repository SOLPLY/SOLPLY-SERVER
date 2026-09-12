package org.sopt.solply_server.domain.auth.service.oauth.kakao;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.service.OAuthService;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.service.SocialUserService;
import org.sopt.solply_server.global.feign.oauth.kakao.KakaoServerClient;
import org.sopt.solply_server.global.feign.oauth.kakao.dto.KakaoSocialUserProfile;
import org.springframework.stereotype.Component;

/**
 * 클래스 레벨 {@code @Transactional(readOnly = true)}를 걷어냈다(2026-09-12). 두 가지가 걸렸다 —
 * 카카오 HTTP 호출이 트랜잭션 안에 들어 있었고, 그 안에서 불린
 * {@code SocialUserService#createOrLoginSocialUser}(쓰기)가 읽기 전용 트랜잭션에 참여했다.
 * 재가입의 {@code reactivate()} 같은 더티 체킹 변경이 조용히 플러시되지 않을 수 있는 모양이다.
 * 지금은 쓰기 트랜잭션의 경계가 {@code SocialUserService}에 있고 HTTP 호출은 그 밖이다 —
 * Google·Apple 구현체가 원래 그런 모양이었다.
 */
@Component
@RequiredArgsConstructor
public class KakaoOAuthServiceImpl implements OAuthService {

    private final KakaoServerClient kakaoServerClient;
    private final SocialUserService socialUserService;

    @Override
    public User socialLogin(final String kakaoAccessToken) {
        KakaoSocialUserProfile profile;
        try {
            profile = kakaoServerClient.getUserInformation("Bearer " + kakaoAccessToken);
        } catch (FeignException e) {
            throw e; // GlobalExceptionHandler로 위임
        }

        return socialUserService.createOrLoginSocialUser(
                SocialPlatform.KAKAO,
                String.valueOf(profile.getId()),
                profile.getEmail()
        );
    }

    @Override
    public boolean support(final SocialPlatform socialPlatform) {
        return socialPlatform == SocialPlatform.KAKAO;
    }
}