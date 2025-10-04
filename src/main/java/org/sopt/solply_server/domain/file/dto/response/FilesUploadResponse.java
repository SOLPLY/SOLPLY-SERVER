package org.sopt.solply_server.domain.file.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.file.dto.PresignedPutUrlInfo;

public record FilesUploadResponse(
        List<PresignedPutUrlInfo> presignedGetUrlInfos
) {

}
