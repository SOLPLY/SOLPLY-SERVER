package org.sopt.solply_server.domain.file.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record FilesUploadRequest(
        @NotEmpty(message = "업로드할 파일 정보가 비어 있을 수 없습니다.")
        List<FileInfo> files
) {
    public record FileInfo(
            @NotBlank(message = "파일명은 필수 값입니다.")
            String fileName,

            @NotBlank(message = "Content-Type은 필수 값입니다.")
            String contentType,

            @NotNull(message = "파일 크기는 필수 값입니다.")
            @Min(value = 1, message = "파일 크기는 1바이트 이상이어야 합니다.")
            Long contentLength
    ) {}
}
