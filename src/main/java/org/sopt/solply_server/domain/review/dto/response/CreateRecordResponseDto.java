package org.sopt.solply_server.domain.review.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.solply_server.domain.review.entity.Record;
import org.sopt.solply_server.domain.review.entity.VisitTime;

public record CreateRecordResponseDto(
    Long recordId,
    Long placeId,
    LocalDate visitedAt,
    VisitTime visitTimeSlot,
    String content,
    List<String> imageUrls,
    LocalDateTime createdAt
) {
  public static CreateRecordResponseDto from(Record record) {
    return new CreateRecordResponseDto(
        record.getId(),
        record.getPlace().getId(),
        record.getVisitedAt(),
        record.getVisitTimeSlot(),
        record.getContent(),
        record.getRecordImages().stream()
            .map(recordImage -> recordImage.getImageUrl())
            .toList(),
        record.getCreatedAt()
    );
  }
}