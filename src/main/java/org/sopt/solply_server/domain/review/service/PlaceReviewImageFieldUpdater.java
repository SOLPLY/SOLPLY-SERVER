package org.sopt.solply_server.domain.review.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.sopt.solply_server.domain.review.repository.PlaceReviewRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.listener.ImageFieldUpdater;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PlaceReviewImageFieldUpdater implements ImageFieldUpdater {

  private final PlaceReviewRepository placeReviewRepository;

  @Override
  public TargetDir supportedDir() {
    return TargetDir.RECORD;
  }

  @Override
  @Transactional
  public void replaceImages(long targetId, List<String> destKeys) {
    PlaceReview placeReview = placeReviewRepository.findById(targetId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.PLACE_REVIEW_NOT_FOUND));

    placeReview.replaceImages(destKeys);
  }
}