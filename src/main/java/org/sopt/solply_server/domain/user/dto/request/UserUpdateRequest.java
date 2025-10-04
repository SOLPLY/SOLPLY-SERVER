package org.sopt.solply_server.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserUpdateRequest(

        @NotBlank(message = "닉네임은 필수입니다")
        @Size(min = 2, max = 8, message = "닉네임은 2자 이상 8자 이하여야 합니다")
        String nickname,

        @NotNull(message = "유저 성향은 필수입니다")
        UserPersona persona,

        @Schema(description = "프로필 이미지 파일 키", example = "dev/uploads/_staging/1", nullable = true)
        String profileImageFileKey
) {
}