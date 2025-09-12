package org.sopt.solply_server.domain.place.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PlaceRequestCreateRequest(
    @NotNull(message = "장소이름은 필수입니다.") String placeName,
    @NotNull(message = "장소유형은 필수입니다.") Long mainTagId,
    @NotNull(message = "주소는 필수입니다.") String address,
    List<Long> subTagAIds,
    List<Long> subTagBIds,
    @NotNull(message = "장소요청사유는 필수입니다.") String reason,
    @NotEmpty(message = "이미지는 최소 1개 이상이여야 합니다.")
    List<ImageRequest> images
){
    public record ImageRequest(
    @NotNull(message = "displayOrder는 필수입니다.")
    Long displayOrder,
    @NotNull(message = "tempFileKey는 필수입니다.")
    Long tempFileKey
    ){}

}