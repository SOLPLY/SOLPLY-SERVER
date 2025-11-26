package org.sopt.solply_server.domain.user.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.global.dto.PagedInfo;

public record UserRequestedPlaceAllGetResponse(
        List<PlacePreviewDto> content,
        PagedInfo pagedInfo
) {

}
