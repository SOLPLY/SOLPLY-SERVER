package org.sopt.solply_server.domain.test.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.test.dto.response.TestLoginResponse;
import org.sopt.solply_server.domain.test.repository.TestRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class TestService {

    private final TestRepository testRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    // 테스트 로그인 (유저 생성)
    @Transactional
    public TestLoginResponse createAndLogin() {
        String email = UUID.randomUUID() + "@test.com";
        String nickname = "user_" + UUID.randomUUID().toString().substring(0, 5);

        User user = testRepository.save(
                User.builder()
                    .email(email)
                    .nickname(nickname)
                    .isNewUser(true)
                    .build()
        );

        return TestLoginResponse.of(
                saveTokenCollection(user.getId()),
                user.isNewUser(),
                user.getId()
        );
    }

    // 테스트 로그인 (기존 유저)
    public TestLoginResponse loginById(Long userId) {
        User user = testRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        return TestLoginResponse.of(
                saveTokenCollection(user.getId()),
                user.isNewUser(),
                user.getId()
        );
    }

    // 테스트 탈퇴
    @Transactional
    public void withdrawUser(Long userId) {
        testRepository.deleteById(userId);
    }

    // 토큰 발급(테스트용)
    private TokenCollectionDto saveTokenCollection(Long userId) {
        TokenCollectionDto newTokens = jwtTokenProvider.createTokenCollection(userId);
        refreshTokenRepository.save(userId, newTokens.refreshToken());
        return newTokens;
    }
}
