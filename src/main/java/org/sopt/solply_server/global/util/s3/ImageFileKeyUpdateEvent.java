package org.sopt.solply_server.global.util.s3;

import java.util.List;

public record ImageFileKeyUpdateEvent(
        Long userId,
        long targetId,
        TargetDir targetDir,
        List<String> sourceKeys,
        FileTransferMode mode
) {
    public static ImageFileKeyUpdateEvent of(
            Long userId,
            Long targetId,
            TargetDir targetDir,
            List<String> sourceKeys,
            FileTransferMode mode
    ) {
        return new ImageFileKeyUpdateEvent(userId, targetId, targetDir, sourceKeys, mode);
    }
}