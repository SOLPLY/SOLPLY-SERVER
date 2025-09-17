package org.sopt.solply_server.domain.test.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.test.dto.response.TestLoginResponse;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.domain.user.service.UserInterestTownService;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class TestService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EntityLoader entityLoader;
    private final UserInterestTownService userInterestTownService;

    // 테스트 로그인 (유저 생성)
    @Transactional
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
                saveTokenCollection(user.getId()),
                user.isNewUser(),
                user.getId()
        );
    }

    // 테스트 로그인 (기존 유저)
    public TestLoginResponse loginById(Long userId) {
        User user = entityLoader.getUser(userId);

        return TestLoginResponse.of(
                saveTokenCollection(user.getId()),
                user.isNewUser(),
                user.getId()
        );
    }

    // 테스트 탈퇴
    @Transactional
    public void withdrawUser(Long userId) {
        userRepository.deleteById(userId);
    }

    // 토큰 발급(테스트용)
    private TokenCollectionDto saveTokenCollection(Long userId) {
        TokenCollectionDto newTokens = jwtTokenProvider.createTokenCollection(userId);
        refreshTokenRepository.save(userId, newTokens.refreshToken());
        return newTokens;
    }
}
