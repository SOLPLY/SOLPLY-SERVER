package org.sopt.solply_server.global.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.util.s3.FileTransferMode;
import org.sopt.solply_server.global.util.s3.ImageFileKeyUpdateEvent;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.S3FileService;
import org.sopt.solply_server.global.util.s3.TargetDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class ImageFileKeyUpdateListener {

    private final S3FileService s3FileService;
    private final Map<TargetDir, ImageFieldUpdater> updaterMap;

    @Autowired
    public ImageFileKeyUpdateListener(
            S3FileService s3FileService,
            List<ImageFieldUpdater> updaters
    ) {
        this.s3FileService = s3FileService;
        // TargetDir 별로 ImageFieldUpdater 매핑
        this.updaterMap = updaters.stream()
                .collect(Collectors.toMap(ImageFieldUpdater::supportedDir, u -> u));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(ImageFileKeyUpdateEvent e) {
        if (e.sourceKeys().isEmpty()) return;

        List<String> destKeys = new ArrayList<>();

        for (String sourceKey : e.sourceKeys()) {
            try {
                String dest = (e.mode() == FileTransferMode.MOVE)
                        ? s3FileService.moveToDir(sourceKey, e.targetId(), e.targetDir())
                        : s3FileService.copyToDir(sourceKey, e.targetId(), e.targetDir());

                if (dest != null) {
                    destKeys.add(dest);
                }
            } catch (Exception ex) {
                log.warn(
                        "파일 처리 실패 sourceKey={}, targetId={}, mode={}",
                        sourceKey, e.targetId(), e.mode(), ex
                );
            }
        }

        if (destKeys.isEmpty()) return;

        ImageFieldUpdater updater = updaterMap.get(e.targetDir());
        if (updater == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        updater.replaceImages(e.targetId(), destKeys);
    }
}