package org.sopt.solply_server.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.SelectedTownDto;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserInterestTownRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserInterestTownRepository userInterestTownRepository;

    public NicknameCheckResponse checkNickname(String nickname) {
        boolean isDuplicated = userRepository.existsByNickname(nickname);

        return NicknameCheckResponse.of(isDuplicated);
    }

    public UserProfileGetResponse getUserProfile(Long userId) {
        User user = userRepository.getReferenceById(userId);

        SelectedTownDto selectedTown = userInterestTownRepository.findByUserWithTown(user)
                .map(userInterestTown -> SelectedTownDto.of(
                        userInterestTown.getTown().getId(),
                        userInterestTown.getTown().getName()
                ))
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        return UserProfileGetResponse.of(user, selectedTown);
    }

}