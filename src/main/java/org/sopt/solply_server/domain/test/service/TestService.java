package org.sopt.solply_server.domain.test.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.auth.service.RefreshTokenService;
import org.sopt.solply_server.domain.test.dto.response.TestLoginResponse;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.domain.user.service.UserInterestTownService;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class TestService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EntityLoader entityLoader;
    private final UserInterestTownService userInterestTownService;
    private final Clock clock;

    /**
     * 테스트 로그인 (유저 생성).
     *
     * <p><b>트랜잭션을 열지 않는 것이 의도다(2026-09-12).</b> 토큰 발급이 사용자 행을 잠그는
     * 별도 트랜잭션이 되면서, 이 메서드가 트랜잭션을 들고 있으면 방금 만든 사용자가 아직
     * 커밋되지 않아 발급 쪽에서 보이지 않는다. {@code userRepository.save}가 자기 트랜잭션으로
     * 커밋하고, 그 다음에 발급이 그 행을 잠근다 — 실제 소셜 로그인과 같은 순서다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TestLoginResponse createAndLogin() {
        String email = UUID.randomUUID() + "@test.com";
//        String nickname = "user_" + UUID.randomUUID().toString().substring(0, 5);

        User user = userRepository.save(User.create(email));
//        Town town1 = entityLoader.getTown(Long.parseLong("2")); // 연희동
//        Town town2 = entityLoader.getTown(Long.parseLong("3")); // 망원동
//        List<Town> initialTowns = new ArrayList<>();
//        initialTowns.add(town1);
//        initialTowns.add(town2);
//
//        userInterestTownService.updateUserInterestTowns(user, initialTowns);

        return TestLoginResponse.of(
                refreshTokenService.issue(user.getId(), SocialPlatform.KAKAO),
                user.isNewUser(),
                user.getId()
        );
    }

    // 테스트 로그인 (기존 유저)
    public TestLoginResponse loginById(Long userId) {
        User user = entityLoader.getUser(userId);

        return TestLoginResponse.of(
                refreshTokenService.issue(user.getId(), SocialPlatform.KAKAO),
                user.isNewUser(),
                user.getId()
        );
    }

    /**
     * 테스트 탈퇴. 실제 탈퇴와 같은 불변식을 지킨다 — 소프트 삭제와 refresh 폐기가 한 트랜잭션에서
     * 커밋된다. 여기만 폐기를 빠뜨리면 같은 행으로 재가입했을 때 옛 계열이 되살아난다.
     */
    @Transactional
    public void withdrawUser(Long userId) {
        refreshTokenRepository.lockUser(userId);
        userRepository.deleteById(userId);

        Instant now = clock.instant();
        refreshTokenRepository.revokeAllByUserId(userId, now.toEpochMilli(), now.getEpochSecond());
    }
}
