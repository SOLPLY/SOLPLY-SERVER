package org.sopt.solply_server.domain.review.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlaceReviewReportType {
  IRRELEVANT_TO_PLACE("장소와 무관한 내용이에요"),
  ABUSIVE_LANGUAGE("욕설 / 비방이 포함되어 있어요"),
  PRIVACY_VIOLATION("타인의 얼굴 / 개인정보가 노출되었어요"),
  FALSE_INFORMATION("허위 정보가 포함되어 있어요"),
  OTHER("기타");

  private final String description;
}
