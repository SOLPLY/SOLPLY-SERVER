package org.sopt.solply_server.domain.place.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PlaceRequestCreateRequest(
    @NotBlank(message = "장소이름은 필수입니다.")
    @Size(max = 255, message = "장소 이름은 255자를 초과할 수 없습니다.")
    String placeName,

    @NotBlank(message = "주소는 필수입니다.")
    @Size(max = 255, message = "주소는 255자를 초과할 수 없습니다.")
    String address,

    @NotNull(message = "장소유형은 필수입니다.")
    Long mainTagId,

    List<Long> subTagAIds,
    List<Long> subTagBIds,

    @NotBlank(message = "장소요청사유는 필수입니다.")
    String reason,

    @NotEmpty(message = "이미지는 최소 1개 이상이어야 합니다.")
    @Valid List<ImageRequest> images
){
    public record ImageRequest(
    @NotNull(message = "displayOrder는 필수입니다.")
    Integer displayOrder,

    @NotNull(message = "tempFileKey는 필수입니다.")
    String tempFileKey
    ){}
}