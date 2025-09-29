package org.sopt.solply_server.domain.user.dto.response;

import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.global.dto.PagedResponse;
import org.springframework.data.domain.Page;

public record UserRequestedPlaceAllGetResponse(
    PagedResponse<PlacePreviewDto> content
) {

}
