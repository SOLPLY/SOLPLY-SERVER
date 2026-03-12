package org.sopt.solply_server.domain.review.repository;

import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceReviewRepository extends JpaRepository<PlaceReview, Long> {
}