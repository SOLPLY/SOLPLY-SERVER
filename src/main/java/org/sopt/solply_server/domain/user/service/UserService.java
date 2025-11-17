package org.sopt.solply_server.domain.user.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.dto.UserPersonaDto;
import org.sopt.solply_server.domain.user.dto.UserPlacePreviewDto;
import org.sopt.solply_server.domain.user.dto.UserTownInfoDto;
import org.sopt.solply_server.domain.user.dto.request.UserInOnboardingUpdateRequest;
import org.sopt.solply_server.domain.user.dto.request.UserUpdateRequest;
import org.sopt.solply_server.domain.user.dto.request.UserTownsUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserInOnboardingUpdateResponse;
import org.sopt.solply_server.domain.user.dto.response.UserUpdateResponse;
import org.sopt.solply_server.domain.user.dto.response.UserPersonaListGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserRequestedPlaceAllGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserTownGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserTownsUpdateResponse;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.sopt.solply_server.domain.user.repository.UserPolicyRepository;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.domain.user.service.mypage.MyPageFacade;
import org.sopt.solply_server.global.dto.PagedResponse;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.PresignedUrlProvider;
import org.sopt.solply_server.global.util.s3.S3FileMoveService;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserValidator userValidator;
    private final EntityLoader entityLoader;
    private final MyPageFacade myPageFacade;
    private final UserRepository userRepository;
    private final PresignedUrlProvider presignedUrlProvider;
    private final S3FileMoveService s3FileMoveService;
    private final UserPolicyRepository userPolicyRepository;
    private final UserPolicyService userPolicyService;

    public NicknameCheckResponse checkNickname(Long userId, String nickname) {
        User user = entityLoader.getUser(userId);
        boolean isDuplicated = false;
        try {
            userValidator.validateNickname(user.getNickname(), nickname);
        } catch (BusinessException e) {
            isDuplicated = true;
        }

        return NicknameCheckResponse.of(isDuplicated);
    }

    public UserProfileGetResponse getUserProfile(Long userId) {
        User user = entityLoader.getUser(userId);
        if (user.getSelectedTownId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_USER_SELECTED_TOWN);
        }
        Town selectedTown = entityLoader.getTown(user.getSelectedTownId());

        List<UserPlacePreviewDto> myPlacePreviews = myPageFacade.getMyPlacesTop3(user)
                .stream()
                .map(place -> UserPlacePreviewDto.of(
                        place.getId(),
                        place.getName(),
                        place.getThumbnailFileKey()
                ))
                .toList();

        return UserProfileGetResponse.of(
                user,
                presignedUrlProvider.createPresignedUrlToRead(user.getProfileImageFileKey()),
                UserTownInfoDto.of(selectedTown.getId(), selectedTown.getName()), myPlacePreviews);
    }


    public UserTownGetResponse getTownsRelatedUser(Long userId) {
        User user = entityLoader.getUser(userId);

        // 선택한 동네가 없는 경우 null 처리
        UserTownInfoDto selectedTown = Optional.ofNullable(user.getSelectedTownId())
                .map(entityLoader::getTown)
                .map(town -> UserTownInfoDto.of(town.getId(), town.getName()))
                .orElse(null);

        // 관심 동네들
//        List<UserTownInfoDto> interestTowns = entityLoader.getInterestTownsWithTownsByIds(userId)
//                .stream()
//                .map(interestTown -> UserTownInfoDto.of(
//                        interestTown.getTown().getId(),
//                        interestTown.getTown().getName()))
//                .toList();

        return UserTownGetResponse.of(selectedTown);
    }

    public UserRequestedPlaceAllGetResponse getPlacesCreatedBy(final Long userId, final int page, final int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PlacePreviewDto> places = myPageFacade.getPlacesCreatedBy(userId, pageable);
        return new UserRequestedPlaceAllGetResponse(
                new PagedResponse<>(
                        places.getContent(),
                        places.getNumber(),
                        places.getSize(),
                        places.getTotalElements(),
                        places.getTotalPages(),
                        places.isLast()
                )
        );
    }

    public UserPersonaListGetResponse getUserPersonaList() {
        List<UserPersonaDto> personaDtos = Arrays.stream(UserPersona.values())
                .map(UserPersonaDto::from)
                .toList();
        return UserPersonaListGetResponse.of(personaDtos);
    }

    @Transactional
    public UserInOnboardingUpdateResponse updateUserInfoInOnboarding(
            final Long userId, UserInOnboardingUpdateRequest request) {
        User user = entityLoader.getUser(userId);
        Town selectedTown = entityLoader.getTown(request.selectedTownId());

        userValidator.validateOnboardingAvailable(user);
        userValidator.validateNickname(user.getNickname(), request.nickname());

        user.updateOnboardingInfo(request.persona(), request.nickname(), request.selectedTownId());

        // 약관 동의 여부 저장
        for (var policyInfo : request.policyAgreementInfos()) {
            userPolicyService.updateUserPolicyAgreement(user, policyInfo.policyId(), policyInfo.isAgree());
        }

        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 무결성에 걸릴 경우가 닉네임 중복밖에 없음
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        return UserInOnboardingUpdateResponse.of(
                user,
                selectedTown
        );
    }

    @Transactional
    public UserUpdateResponse updateUserInfo(final Long userId, UserUpdateRequest request) {
        User user = entityLoader.getUser(userId);
        UserPersona updatedPersona = request.persona() != null
                ? request.persona()
                : user.getPersona();

        String updatedNickname = request.nickname() != null
                ? request.nickname()
                : user.getNickname();

        if (!updatedNickname.equals(user.getNickname())) {
            userValidator.validateNickname(user.getNickname(), updatedNickname);
        }

        String updatedProfileImageFileKey = user.getProfileImageFileKey();
        String profileImageUrl = null;

        if (request.profileImageFileKey() != null) {
            updatedProfileImageFileKey = s3FileMoveService.moveToDir(
                    request.profileImageFileKey(),
                    userId,
                    TargetDir.USER_PROFILE
            );
            profileImageUrl = presignedUrlProvider.createPresignedUrlToRead(updatedProfileImageFileKey);
        } else if (updatedProfileImageFileKey != null) {
            profileImageUrl = presignedUrlProvider.createPresignedUrlToRead(updatedProfileImageFileKey);
        }

        user.updateuserInfo(updatedPersona, updatedNickname, updatedProfileImageFileKey);

        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 무결성에 걸릴 경우가 닉네임 중복밖에 없음
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        return UserUpdateResponse.of(user, profileImageUrl);
    }

    @Transactional
    public UserTownsUpdateResponse updateUserTowns(final Long userId, UserTownsUpdateRequest request) {
        User user = entityLoader.getUser(userId);
//        List<Town> towns = request.favoriteTownIdList().stream()
//                .map(entityLoader::getTown)
//                .toList();
//        userInterestTownService.updateUserInterestTowns(user, towns);
        Town selectedTown = entityLoader.getTown(request.selectedTownId());
        user.updateSelectedTown(request.selectedTownId());

        return UserTownsUpdateResponse.of(
                UserTownInfoDto.of(request.selectedTownId(), selectedTown.getName())
        );
    }
}