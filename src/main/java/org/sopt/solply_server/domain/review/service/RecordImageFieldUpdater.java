package org.sopt.solply_server.domain.review.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.review.entity.Record;
import org.sopt.solply_server.domain.review.repository.RecordRepository;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.listener.ImageFieldUpdater;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RecordImageFieldUpdater implements ImageFieldUpdater {

  private final RecordRepository recordRepository;

  @Override
  public TargetDir supportedDir() {
    return TargetDir.RECORD;
  }

  @Override
  @Transactional
  public void replaceImages(long targetId, List<String> destKeys) {
    Record record = recordRepository.findById(targetId)
        .orElseThrow(() -> new EntityNotFoundException(ErrorCode.RECORD_NOT_FOUND));

    record.replaceImages(destKeys);
  }
}