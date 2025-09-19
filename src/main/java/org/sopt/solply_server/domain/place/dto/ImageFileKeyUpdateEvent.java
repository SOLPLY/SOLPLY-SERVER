package org.sopt.solply_server.domain.place.dto;

import com.drew.lang.annotations.Nullable;
import java.util.List;
import org.sopt.solply_server.global.util.s3.TargetDir;

public record ImageFileKeyUpdateEvent(
        Long userId,
        long targetId,
        TargetDir targetDir,
        List<String> stagingKeys
) {
    public static ImageFileKeyUpdateEvent of(
            final Long userId,
            final long targetId,
            final TargetDir targetDir,
            final List<String> stagingKeys
    ) {
        return new ImageFileKeyUpdateEvent(userId, targetId, targetDir, stagingKeys);
    }
}