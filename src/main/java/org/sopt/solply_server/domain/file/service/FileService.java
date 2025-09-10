package org.sopt.solply_server.domain.file.service;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.file.dto.PresignedUrlInfo;
import org.sopt.solply_server.domain.file.dto.request.FilesUploadRequest;
import org.sopt.solply_server.domain.file.dto.response.FilesUploadResponse;
import org.sopt.solply_server.global.util.s3.PresignedUrlProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FileService {
    private final PresignedUrlProvider presignedUrlProvider;
    private static final Duration PRESIGN_TTL = java.time.Duration.ofMinutes(10);

    public FilesUploadResponse createPresignedUrlToUpload(@Valid FilesUploadRequest request) {

        List<PresignedUrlInfo> presignedUrlInfos = request.files().stream()
                        .map(file ->
                            presignedUrlProvider.createPresignedUrlInfo(
                                    file.fileName(), PRESIGN_TTL
                            )
                        ).toList();
        return new FilesUploadResponse(
                presignedUrlInfos
        );
    }
}