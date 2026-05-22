package org.sopt.solply_server.domain.review.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewReportRequest;
import org.sopt.solply_server.domain.review.dto.response.CreatePlaceReviewReportResponse;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.entity.PlaceReviewReport;
import org.sopt.solply_server.domain.review.repository.PlaceReviewReportRepository;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.exception.BusinessValidationException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceReviewReportService {

  private final PlaceReviewReportRepository placeReviewReportRepository;
  private final PlaceReviewRepository placeReviewRepository;
  private final EntityLoader entityLoader;

  @Transactional
  public CreatePlaceReviewReportResponse createReviewReport(
      final Long userId,
      final Long reviewId,
      final CreatePlaceReviewReportRequest request
  ) {
    User user = entityLoader.getUser(userId);
    PlaceReview placeReview = placeReviewRepository.findById(reviewId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.PLACE_REVIEW_NOT_FOUND));

    if (placeReview.getUser().getId().equals(userId)) {
      throw new BusinessValidationException(ErrorCode.FORBIDDEN_SELF_REVIEW_REPORT);
    }

    if (placeReviewReportRepository.existsByUserIdAndPlaceReviewId(userId, reviewId)) {
      throw new BusinessValidationException(ErrorCode.ALREADY_REPORTED_REVIEW);
    }

    PlaceReviewReport saved = placeReviewReportRepository.save(
        PlaceReviewReport.create(placeReview, user, request.reportType())
    );

    log.info("리뷰 신고 접수 완료 - userId: {}, reviewId: {}, reportId: {}, reportType: {}",
        userId, reviewId, saved.getId(), request.reportType());

    return CreatePlaceReviewReportResponse.from(saved);
  }
}
