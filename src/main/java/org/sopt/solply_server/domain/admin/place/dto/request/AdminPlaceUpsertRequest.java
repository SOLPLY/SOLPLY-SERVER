package org.sopt.solply_server.domain.admin.place.dto.request;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.sopt.solply_server.domain.place.entity.SnsPlatform;

public record AdminPlaceUpsertRequest(

        @NotBlank(message = "장소명은 필수입니다.")
        @Size(max = 100, message = "장소명은 100자를 초과할 수 없습니다.")
        String name,

        @NotBlank(message = "소개는 필수입니다.")
        @Size(max = 500, message = "소개는 500자를 초과할 수 없습니다.")
        String introduction,

        @Size(max = 255, message = "주소는 255자를 초과할 수 없습니다.")
        String address,

        @NotNull(message = "위도는 필수입니다.")
        @DecimalMin(value = "-90.0", message = "위도 범위가 올바르지 않습니다.")
        @DecimalMax(value = "90.0", message = "위도 범위가 올바르지 않습니다.")
        Double latitude,

        @NotNull(message = "경도는 필수입니다.")
        @DecimalMin(value = "-180.0", message = "경도 범위가 올바르지 않습니다.")
        @DecimalMax(value = "180.0", message = "경도 범위가 올바르지 않습니다.")
        Double longitude,

        @NotNull(message = "townId는 필수입니다.")
        Long townId,

        @NotNull(message = "mainTagId는 필수입니다.")
        Long mainTagId,

        @NotEmpty(message = "option1TagIds는 1개 이상 필수입니다.")
        List<@NotNull Long> option1TagIds,

        List<@NotNull Long> option2TagIds,

        List<@NotBlank(message = "imageFileKeys에는 공백 문자열이 올 수 없습니다.") String> imageFileKeys,

        @Size(max = 30, message = "연락처는 30자를 초과할 수 없습니다.")
        String contactNumber,

        @Size(max = 255, message = "영업시간은 255자를 초과할 수 없습니다.")
        String openingHours,

        Map<SnsPlatform, String> snsLinks,

        List<@NotBlank(message = "placeCheckpoints에는 공백 문자열이 올 수 없습니다.") String> placeCheckpoints
) {}