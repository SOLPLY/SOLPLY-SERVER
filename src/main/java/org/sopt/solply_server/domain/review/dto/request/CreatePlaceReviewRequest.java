package org.sopt.solply_server.domain.review.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.sopt.solply_server.domain.review.entity.VisitTime;

public record CreatePlaceReviewRequest(

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

    List<String> imageKeys,

    // 한시 조치: 앱이 평점을 아직 보내지 않아 선택값으로 둔다. 값이 없으면 서비스가 중립값 3으로
    // 채워 저장한다. 앱이 평점을 보내기 시작하면 @NotNull과 서비스의 null 처리를 함께 되살린다.
    @Min(value = 1, message = "평점은 1점 이상이어야 합니다.")
    @Max(value = 5, message = "평점은 5점 이하여야 합니다.")
    Integer rating
) {
}