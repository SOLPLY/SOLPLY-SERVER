package org.sopt.solply_server.global.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.util.s3.ImageFileDeleteEvent;
import org.sopt.solply_server.global.util.s3.S3FileService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageFileDeleteListener {

  private final S3FileService s3FileService;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDeleted(ImageFileDeleteEvent event) {
    for (String imageKey : event.imageKeys()) {
      try {
        s3FileService.deleteFile(imageKey);
      } catch (Exception e) {
        log.warn("S3 파일 삭제 실패 imageKey={}", imageKey, e);
      }
    }
  }
}