package org.sopt.solply_server.domain.review.repository;

import org.sopt.solply_server.domain.review.entity.PlaceReviewSummary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceReviewSummaryRepository extends JpaRepository<PlaceReviewSummary, Long> {
}
