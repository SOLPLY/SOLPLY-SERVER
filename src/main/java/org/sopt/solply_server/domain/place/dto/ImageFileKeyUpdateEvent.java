package org.sopt.solply_server.domain.place.dto;

import com.drew.lang.annotations.Nullable;
import java.util.List;
import org.sopt.solply_server.global.util.s3.TargetDir;

public record ImageFileKeyUpdateEvent(
        long targetId,
        TargetDir targetDir,
        Long userId,
        String uploadToken,
        List<String> stagingKeys
) {}