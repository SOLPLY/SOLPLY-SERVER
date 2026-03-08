package org.sopt.solply_server.domain.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.user.entity.SocialUserInfo;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.SocialUserInfoRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.domain.user.service.event.UserRegistrationEvent;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SocialUserService {

    private final SocialUserInfoRepository socialUserInfoRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public User createOrLoginSocialUser(
            SocialPlatform platform,
            String socialId,
            String email
    ) {
        validateSocialPayload(socialId);

        String socialCode = createSocialCode(platform, socialId);

        // 1) socialCode 우선 로그인
        Optional<Long> userIdBySocialCode = socialUserInfoRepository.findAnyUserIdBySocialCode(socialCode);
        if (userIdBySocialCode.isPresent()) {
            User user = userIdBySocialCode
                    .flatMap(userRepository::findAnyById)   // Long → Optional<User>
                    .map(this::reactivateIfDeleted)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

            if (email != null && user.getEmail() == null) {
                user.updateEmail(email);
            }
            return user;
        }

        // 2) 해당 소셜 링크가 없으면, email로 기존 유저 연동 시도
        User user = null;
        boolean isNewUser = false; // 신규 유저 여부 체크 플래그

        if (email != null) {
            user = userRepository.findAnyByEmail(email)
                    .map(this::reactivateIfDeleted)
                    .orElse(null);
        }

        // 3) 없으면 신규 생성
        if (user == null) {
            user = userRepository.save(User.create(email)); // email은 null 가능하게
            isNewUser = true;
        }

        // 4) 소셜 링크 생성
        // socialCode는 유니크라서 여기서 동시성 안전하게 처리하는 게 좋음(아래 참고)
        linkSocialAccount(user, platform, socialId);

        if (isNewUser) {
            eventPublisher.publishEvent(new UserRegistrationEvent(user, platform));
        }


        return user;
    }

    private void validateSocialPayload(String socialId) {
        if (socialId == null || socialId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SOCIAL_LOGIN_PAYLOAD);
        }
    }

    private User reactivateIfDeleted(User user) {
        if (user.isDeleted()) {
            user.reactivate();
        }
        return user;
    }

    private String createSocialCode(SocialPlatform socialPlatform, String socialId) {
        return socialPlatform.name() + "_" + socialId;
    }

    private void linkSocialAccount(final User user, final SocialPlatform platform, final String socialId) {
        SocialUserInfo socialInfo = SocialUserInfo.create(
                user,
                platform,
                socialId
        );
        socialUserInfoRepository.save(socialInfo);
    }

}