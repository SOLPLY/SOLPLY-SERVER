package org.sopt.solply_server.domain.file.service;

import static java.util.stream.Collectors.toList;

import jakarta.validation.Valid;
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

    PresignedUrlProvider presignedUrlProvider;

    public FilesUploadResponse createPresignedUrlToUpload(@Valid FilesUploadRequest request) {
        List<PresignedUrlInfo> presignedUrlInfos = request.files().stream()
                        .map(file -> new PresignedUrlInfo(
                                file.fileName(),
                                presignedUrlProvider.generatePresignedUrl(
                                        file.fileName(), file.contentType(), file.contentLength())
                            )
                        ).toList();
        return new FilesUploadResponse(
                presignedUrlInfos
        );
    }
}