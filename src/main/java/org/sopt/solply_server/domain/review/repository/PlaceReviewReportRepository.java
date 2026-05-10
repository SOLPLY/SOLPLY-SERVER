package org.sopt.solply_server.domain.review.repository;

import org.sopt.solply_server.domain.review.entity.PlaceReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceReviewReportRepository extends JpaRepository<PlaceReviewReport, Long> {

  boolean existsByUserIdAndPlaceReviewId(Long userId, Long placeReviewId);
}
