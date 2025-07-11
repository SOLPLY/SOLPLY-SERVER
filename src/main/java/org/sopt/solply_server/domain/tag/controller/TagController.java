package org.sopt.solply_server.domain.tag.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.tag.dto.TagResponse;
import org.sopt.solply_server.domain.tag.service.TagService;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "태그 API", description = "태그 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/tags")
public class TagController {

    private final TagService tagService;

    @Operation(summary = "태그조회", description = "장소유형 태그를 조회합니다.")
    @GetMapping
    public ResponseEntity<CustomApiResponse<TagResponse>> findTags(
            @RequestParam(required = false) Long parent
    ) {
        TagResponse response = tagService.findTags(parent);
        return CustomApiResponse.success("태그 조회에 성공했습니다", response);
    }
}