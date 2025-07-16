package org.sopt.solply_server.domain.user.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UserTownsUpdateRequest(
        @NotNull(message = "동네 선택은 필수입니다")
        Long selectedTownId,

        @NotEmpty(message = "관심 동네는 필수입니다")
        List<Long> favoriteTownIdList
) {

}