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

        List<@NotBlank(message = "placeCheckpoints에는 공백 문자열이 올 수 없습니다.") String> placeCheckpoints,

        /**
         * 이 수정이 <b>사용자의 목록을 새로 시작시킬지</b>. 생략하면 {@code false} — 지금 스크롤을
         * 내리고 있는 사용자는 하던 페이지를 계속 받는다.
         *
         * <p><b>기본값이 "유지"인 이유.</b> 어드민 수정 한 건마다 목록을 새로 시작시키면, 운영
         * 시간대에 장소 하나를 고칠 때마다 그 순간 스크롤 중이던 사용자가 전부 첫 페이지로
         * 되돌아간다. 장소 추가·삭제·동네 이동도 마찬가지라 <b>자동으로는 올리지 않는다.</b>
         *
         * <p><b>유지의 대가.</b> 이번 수정이 순서나 소속을 건드렸다면, 이어 보는 사용자에게
         * 항목 하나가 겹치거나 빠져 보일 수 있다. 대량 정리처럼 그 어긋남이 눈에 띌 작업에서는
         * {@code true}로 보내 목록을 명시적으로 새로 시작시킨다.
         */
        Boolean restartPlaceList
) {

    /** 생략을 "유지"로 읽는 자리. 부르는 쪽마다 null 검사를 흩지 않는다. */
    public boolean restartsPlaceList() {
        return Boolean.TRUE.equals(restartPlaceList);
    }
}