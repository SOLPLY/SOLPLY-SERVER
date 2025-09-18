package org.sopt.solply_server.domain.place.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.S3FileMoveService;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class ImageFileKeyUpdateListener {

    private final S3FileMoveService s3FileMoveService;
    private final Map<TargetDir, ImageFieldUpdater> updaterMap;

    @Autowired
    public ImageFileKeyUpdateListener(
            S3FileMoveService s3FileMoveService,
            List<ImageFieldUpdater> updaters
    ) {
        this.s3FileMoveService = s3FileMoveService;
        // TargetDir 별로 ImageFieldUpdater 매핑
        this.updaterMap = updaters.stream()
                .collect(Collectors.toMap(ImageFieldUpdater::supportedDir, u -> u));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(ImageFileKeyUpdateEvent e) {
        // 옮길 stagingKey 찾기
        List<String> stagingKeys = (e.stagingKeys() != null && !e.stagingKeys().isEmpty())
                ? e.stagingKeys()
                : s3FileMoveService.listByToken(e.userId(), e.uploadToken());

        if (stagingKeys.isEmpty()) return;

        // 파일 위치 이동
        List<String> destKeys = new ArrayList<>();
        for (String stagingKey : stagingKeys) {
            try {
                String dest = s3FileMoveService.moveToDir(stagingKey, e.targetId(), e.targetDir());
                destKeys.add(dest);
            } catch (Exception ex) {
                log.warn("파일 경로 변경 실패 / stagingKey={}, targetId={}, dir={}", stagingKey, e.targetId(), e.targetDir(), ex);
            }
        }
        if (destKeys.isEmpty()) return;

        // DB에 반영
        ImageFieldUpdater updater = updaterMap.get(e.targetDir());
        if (updater == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        updater.replaceImages(e.targetId(), destKeys);
    }
}