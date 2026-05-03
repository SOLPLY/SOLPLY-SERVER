package org.sopt.solply_server.domain.review.service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.review.dto.request.CreatePlaceReviewRequest;
import org.sopt.solply_server.domain.review.dto.response.CreatePlaceReviewResponse;
import org.sopt.solply_server.domain.review.dto.response.GetMyReviewListResponse;
import org.sopt.solply_server.domain.review.dto.response.GetMyReviewPreviewResponse;
import org.sopt.solply_server.domain.review.dto.response.GetPlaceReviewListResponse;
import org.sopt.solply_server.domain.review.dto.response.MyReviewListItem;
import org.sopt.solply_server.domain.review.dto.response.MyReviewPreviewItem;
import org.sopt.solply_server.domain.review.dto.response.PlaceReviewListItem;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessValidationException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.FileTransferMode;
import org.sopt.solply_server.global.util.s3.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceReviewServiceImpl implements PlaceReviewService {

  private static final int MAX_IMAGE_COUNT = 5;

  private final PlaceReviewRepository placeReviewRepository;
  private final UserRepository userRepository;
  private final PlaceRepository placeRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final ImageUrlProvider imageUrlProvider;

  @Override
  @Transactional
  public CreatePlaceReviewResponse createReview(Long userId, CreatePlaceReviewRequest request) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));

    Place place = placeRepository.findActiveById(request.placeId())
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));

    validateRequest(request);

    PlaceReview placeReview = PlaceReview.create(
        user,
        place,
        request.visitedAt(),
        request.visitTimeSlot(),
        request.content().trim()
    );

    PlaceReview savedPlaceReview = placeReviewRepository.save(placeReview);

    List<String> imageKeys = request.imageKeys() == null
        ? Collections.emptyList()
        : request.imageKeys();

    if (!imageKeys.isEmpty()) {
      eventPublisher.publishEvent(
          new ImageFileKeyUpdateEvent(
              userId,
              savedPlaceReview.getId(),
              TargetDir.RECORD,
              imageKeys,
              FileTransferMode.MOVE
          )
      );
    }

    return CreatePlaceReviewResponse.from(savedPlaceReview);
  }

  @Override
  public GetPlaceReviewListResponse getPlaceReviews(Long placeId) {
    placeRepository.findActiveById(placeId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));

    List<PlaceReviewListItem> reviews = placeReviewRepository
        .findAllByPlaceIdOrderByCreatedAtDesc(placeId)
        .stream()
        .map(placeReview -> PlaceReviewListItem.from(placeReview, imageUrlProvider))
        .toList();

    return GetPlaceReviewListResponse.of(reviews);
  }

  private void validateRequest(CreatePlaceReviewRequest request) {
    validateVisitedAt(request.visitedAt());
    validateContent(request.content());
    validateImages(request.imageKeys());
  }

  private void validateVisitedAt(LocalDate visitedAt) {
    if (visitedAt.isAfter(LocalDate.now())) {
      throw new BusinessValidationException(ErrorCode.INVALID_VISIT_DATE);
    }
  }

  private void validateContent(String content) {
    if (!StringUtils.hasText(content)) {
      throw new BusinessValidationException(ErrorCode.PLACE_REVIEW_CONTENT_BLANK);
    }

    String trimmed = content.trim();
    if (trimmed.length() < 10 || trimmed.length() > 500) {
      throw new BusinessValidationException(ErrorCode.INVALID_PLACE_REVIEW_CONTENT_LENGTH);
    }
  }

  private void validateImages(List<String> imageKeys) {
    if (imageKeys == null) {
      return;
    }

    if (imageKeys.size() > MAX_IMAGE_COUNT) {
      throw new BusinessValidationException(ErrorCode.PLACE_REVIEW_IMAGE_LIMIT_EXCEEDED);
    }
  }
  @Override
  public GetMyReviewListResponse getMyReviews(Long userId) {

    userRepository.findById(userId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));

    List<MyReviewListItem> reviews = placeReviewRepository
        .findAllByUserIdOrderByCreatedAtDesc(userId)
        .stream()
        .map(review -> MyReviewListItem.from(review, imageUrlProvider))
        .toList();

    return GetMyReviewListResponse.of(reviews);
  }

  @Override
  public GetMyReviewPreviewResponse getMyReviewPreview(Long userId) {

    userRepository.findById(userId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));

    List<PlaceReview> reviews = placeReviewRepository
        .findMyReviewsWithFetchJoin(userId, PageRequest.of(0, 4));

    boolean hasMore = reviews.size() > 3;

    List<MyReviewPreviewItem> result = reviews.stream()
        .limit(3)
        .map(review -> MyReviewPreviewItem.from(review, imageUrlProvider))
        .toList();

    return GetMyReviewPreviewResponse.of(result, hasMore);
  }
}