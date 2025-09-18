package org.sopt.solply_server.domain.file.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.file.dto.request.FilesUploadRequest;
import org.sopt.solply_server.domain.file.dto.response.FilesUploadResponse;
import org.sopt.solply_server.domain.file.service.FileService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "파일 업로드 API", description = "S3 관리")
@Slf4j
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping(value = "/presigned-urls")
    public ResponseEntity<CustomApiResponse<FilesUploadResponse>> createPresignedUrlToUpload(
            @CurrentUserId Long userId,
            @Valid @RequestBody FilesUploadRequest request
    ) {
        return CustomApiResponse.success(
                "업로드용 presigned url 생성에 성공했습니다.",
                fileService.createPresignedUrlToUpload(userId, request)
        );
    }

}