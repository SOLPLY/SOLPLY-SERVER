package org.sopt.solply_server.domain.admin.tag.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagActivationRequest;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagDetailsResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagListResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagActivationResponse;
import org.sopt.solply_server.domain.admin.tag.facade.AdminTagFacade;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "어드민(태그 데이터 관리) API", description = "태그 데이터 관리 Admin용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/tags")
public class AdminTagController {

    private final AdminTagFacade adminTagFacade;

    @Operation(summary = "어드민 태그 생성", description = "태그를 생성합니다.")
    @PostMapping
    public ResponseEntity<CustomApiResponse<Long>> createTag(
            @Valid @RequestBody AdminTagUpsertRequest request
    ) {
        return CustomApiResponse.success(
                "태그 생성 성공",
                adminTagFacade.createTag(request)
        );
    }

    @Operation(summary = "어드민 태그 리스트 조회", description = "태그 목록을 조회합니다. (타입/이름/상위태그명/활성여부)")
    @GetMapping
    public ResponseEntity<CustomApiResponse<AdminTagListResponse>> getTags() {
        return CustomApiResponse.success(
                "태그 리스트 조회 성공",
                adminTagFacade.getTags()
        );
    }

    @Operation(summary = "어드민 태그 상세 조회", description = "태그 상세를 조회합니다. (페르소나 매핑 포함)")
    @GetMapping("/{id}")
    public ResponseEntity<CustomApiResponse<AdminTagDetailsResponse>> getTagDetails(
            @Parameter(description = "태그 ID", required = true, example = "1")
            @PathVariable Long id
    ) {
        return CustomApiResponse.success(
                "태그 상세 조회 성공",
                adminTagFacade.getTagDetails(id)
        );
    }

    @Operation(summary = "어드민 태그 수정", description = "태그 정보를 수정합니다. (타입/상위태그/이름/활성여부/페르소나 매핑)")
    @PutMapping("/{id}")
    public ResponseEntity<CustomApiResponse<Long>> updateTag(
            @Parameter(description = "태그 ID", required = true, example = "1")
            @PathVariable Long id,
            @Valid @RequestBody AdminTagUpsertRequest request
    ) {
        return CustomApiResponse.success(
                "태그 수정 성공",
                adminTagFacade.updateTag(id, request)
        );
    }

    @Operation(summary = "어드민 태그 활성/비활성", description = "태그 활성 상태를 변경합니다. 비활성화 시 하위 태그도 함께 비활성화됩니다.")
    @PatchMapping("/{id}")
    public ResponseEntity<CustomApiResponse<AdminTagActivationResponse>> toggleTagActive(
            @Parameter(description = "태그 ID", required = true, example = "1")
            @PathVariable Long id,
            @Valid @RequestBody AdminTagActivationRequest request
    ) {
        return CustomApiResponse.success(
                "태그 활성 상태 변경 성공",
                adminTagFacade.toggleActive(id, request)
        );
    }
}
