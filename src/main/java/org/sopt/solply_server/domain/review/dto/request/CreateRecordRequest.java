package org.sopt.solply_server.domain.review.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.sopt.solply_server.domain.review.entity.VisitTime;

public record CreateRecordRequest(

    @NotNull(message = "장소 ID는 필수입니다.")
    Long placeId,

    @NotNull(message = "방문 날짜는 필수입니다.")
    @PastOrPresent(message = "방문 날짜는 오늘 또는 이전 날짜만 선택할 수 있습니다.")
    LocalDate visitedAt,

    @NotNull(message = "방문 시간대는 필수입니다.")
    VisitTime visitTimeSlot,

    @NotBlank(message = "오늘의 기록은 필수입니다.")
    @Size(min = 10, max = 500, message = "오늘의 기록은 10자 이상 500자 이하여야 합니다.")
    String content,

    List<String> imageKeys
) {
}