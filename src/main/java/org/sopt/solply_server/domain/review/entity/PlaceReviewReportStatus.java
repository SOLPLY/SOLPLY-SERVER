package org.sopt.solply_server.domain.review.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlaceReviewReportStatus {
  PENDING("검토 대기"),
  RESOLVED("해결됨");

  private final String description;
}
