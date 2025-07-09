package org.sopt.solply_server.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.sopt.solply_server.domain.user.entity.UserPersona;

public record UserUpdateRequest(
        @NotBlank(message = "관심 동네는 필수입니다")
        String favoriteTowns,

        @NotNull(message = "유저 성향은 필수입니다")
        UserPersona persona,

        @NotBlank(message = "닉네임은 필수입니다")
        @Size(min = 2, max = 8, message = "닉네임은 2자 이상 8자 이하여야 합니다")
        String nickname
) {
}