package org.sopt.solply_server.domain.report.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.sopt.solply_server.domain.report.entity.PlaceReportType;

import java.util.List;

public record PlaceReportCreateRequest(
        @NotNull(message = "제보 유형을 선택해주세요.")
        PlaceReportType reportType,

        @Size(max = 200, message = "제보 내용은 200자 이하로 작성해주세요.")
        String content,

        @Size(max = 3, message = "이미지는 최대 3개까지 첨부할 수 있습니다.")
        List<String> imageKeys
) {
}