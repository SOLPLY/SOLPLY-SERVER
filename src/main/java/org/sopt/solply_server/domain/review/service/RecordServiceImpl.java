package org.sopt.solply_server.domain.review.service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.review.dto.request.CreateRecordRequestDto;
import org.sopt.solply_server.domain.review.dto.response.CreateRecordResponseDto;
import org.sopt.solply_server.domain.review.repository.RecordRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessValidationException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.FileTransferMode;
import org.sopt.solply_server.global.util.s3.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.sopt.solply_server.domain.review.entity.Record;
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordServiceImpl implements RecordService {

  private static final int MAX_IMAGE_COUNT = 5;

  private final RecordRepository recordRepository;
  private final UserRepository userRepository;
  private final PlaceRepository placeRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional
  public CreateRecordResponseDto createRecord(Long userId, CreateRecordRequestDto request) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));

    Place place = placeRepository.findActiveById(request.placeId())
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE));

    validateRequest(request);

    Record record = Record.create(
        user,
        place,
        request.visitedAt(),
        request.visitTimeSlot(),
        request.content().trim()
    );

    Record savedRecord = recordRepository.save(record);

    List<String> imageKeys = request.imageKeys() == null ? Collections.emptyList() : request.imageKeys();
    if (!imageKeys.isEmpty()) {
      eventPublisher.publishEvent(
          new ImageFileKeyUpdateEvent(
              userId,
              savedRecord.getId(),
              TargetDir.RECORD,
              imageKeys,
              FileTransferMode.MOVE
          )
      );
    }

    return CreateRecordResponseDto.from(savedRecord);
  }

  private void validateRequest(CreateRecordRequestDto request) {
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
      throw new BusinessValidationException(ErrorCode.RECORD_CONTENT_BLANK);
    }

    String trimmed = content.trim();
    if (trimmed.length() < 10 || trimmed.length() > 500) {
      throw new BusinessValidationException(ErrorCode.INVALID_RECORD_CONTENT_LENGTH);
    }
  }

  private void validateImages(List<String> imageKeys) {
    if (imageKeys == null) return;

    if (imageKeys.size() > MAX_IMAGE_COUNT) {
      throw new BusinessValidationException(ErrorCode.RECORD_IMAGE_LIMIT_EXCEEDED);
    }
  }
}