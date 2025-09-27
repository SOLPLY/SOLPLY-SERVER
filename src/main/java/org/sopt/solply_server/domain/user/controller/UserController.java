package org.sopt.solply_server.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.user.dto.request.UserWithdrawRequest;
import org.sopt.solply_server.domain.user.dto.request.UserTownsUpdateRequest;
import org.sopt.solply_server.domain.user.dto.response.NicknameCheckResponse;
import org.sopt.solply_server.domain.user.dto.response.UserProfileGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserRequestedPlaceAllGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserTownGetResponse;
import org.sopt.solply_server.domain.user.dto.response.UserTownsUpdateResponse;
import org.sopt.solply_server.domain.user.dto.response.UserWithdrawReasonAllGetResponse;
import org.sopt.solply_server.domain.user.service.UserService;
import org.sopt.solply_server.domain.user.service.UserWithdrawService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "유저 API", description = "사용자 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService userService;
    private final UserWithdrawService userWithdrawService;

    @Operation(summary = "닉네임 중복 검사", description = "닉네임 사용 가능 여부를 확인합니다.")
    @GetMapping("/check-nickname")
    public ResponseEntity<CustomApiResponse<NicknameCheckResponse>> checkNickname(
            @CurrentUserId Long currentUserId,
            @Parameter(description = "확인할 닉네임", required = true)
            @RequestParam("nickname")
            @NotBlank(message = "닉네임은 필수입니다")
            @Size(min = 2, max = 8, message = "닉네임은 2자 이상 8자 이하여야 합니다")
            String nickname
    ) {
        NicknameCheckResponse response = userService.checkNickname(currentUserId, nickname);
        return CustomApiResponse.success("닉네임 중복검사에 성공했습니다", response);
    }

    @Operation(summary = "회원의 동네 관련 정보 업데이트", description = "회원의 선택한 동네 및 관심 동네들을 업데이트합니다.")
    @PatchMapping("/towns")
    public ResponseEntity<CustomApiResponse<UserTownsUpdateResponse>> updateUserTowns(
            @CurrentUserId Long userId,
            @Valid @RequestBody UserTownsUpdateRequest request
    ) {
        return CustomApiResponse.success("회원의 동네 정보 업데이트에 성공했습니다",
                userService.updateUserTowns(userId, request));
    }
  
    @Operation(summary = "회원 정보 조회", description = "현재 로그인한 사용자의 정보를 조회합니다.")
    @GetMapping
    public ResponseEntity<CustomApiResponse<UserProfileGetResponse>> getUserProfile(
            @CurrentUserId Long userId
    ) {
        UserProfileGetResponse response = userService.getUserProfile(userId);
        return CustomApiResponse.success("유저 정보 조회에 성공하였습니다.", response);
    }

    @Operation(summary = "동네 관련 정보 조회", description = "유저가 선택한 동네를 조회합니다.")
    @GetMapping("/towns")
    public ResponseEntity<CustomApiResponse<UserTownGetResponse>> getUserTowns(
            @CurrentUserId Long userId
    ) {
        return CustomApiResponse.success("유저의 동네 정보 조회에 성공하였습니다.",
                userService.getTownsRelatedUser(userId));
    }

    @Operation(summary = "유저가 등록한 장소 조회", description = "특정 유저가 등록한 장소들을 조회합니다.")
    @GetMapping("/{userId}/places")
    public ResponseEntity<CustomApiResponse<UserRequestedPlaceAllGetResponse>> getMyPlaces(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return CustomApiResponse.success("유저가 등록한 장소 조회에 성공했습니다.",
                userService.getPlacesCreatedBy(userId, page, size));
    }

    @Operation(summary = "회원 탈퇴 사유 리스트 조회", description = "회원 탈퇴 사유 리스트를 조회합니다.")
    @GetMapping("/withdraw/reasons")
    public ResponseEntity<CustomApiResponse<UserWithdrawReasonAllGetResponse>> getUserWithdrawReasons() {
        return CustomApiResponse.success("회원 탈퇴 사유 리스트 조회에 성공했습니다",
                userWithdrawService.getAllUserWithdrawReasons());
    }

    @Operation(summary = "회원 탈퇴", description = "회원 탈퇴를 진행합니다.")
    @DeleteMapping("/withdraw")
    public ResponseEntity<CustomApiResponse<Void>> withdrawUser(
            @CurrentUserId Long userId,
            @RequestBody @Valid UserWithdrawRequest request
    ) {
        userService.withdraw(userId, request);
        return CustomApiResponse.success("회원 탈퇴에 성공했습니다", null);
    }

}
