package org.sopt.solply_server.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userRepository.existsByNickname(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

}