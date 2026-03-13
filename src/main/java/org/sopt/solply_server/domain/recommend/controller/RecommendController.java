package org.sopt.solply_server.domain.recommend.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.sopt.solply_server.domain.recommend.dto.EmbeddingPlaceRecommendRequest;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.recommend.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.recommend.dto.response.EmbeddingPlaceRecommendGetResponse;
import org.sopt.solply_server.domain.recommend.dto.response.PlaceRecommendationGetResponse;
import org.sopt.solply_server.domain.recommend.service.EmbeddingRecommendService;
import org.sopt.solply_server.domain.recommend.service.RecommendService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "추천 API", description = "추천 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/recommend")
public class RecommendController {

    private final RecommendService recommendService;
    private final EmbeddingRecommendService embeddingRecommendService;

    @Operation(summary = "장소 추천 조회", description = "장소 추천을 위한 썸네일 리스트를 조회합니다.")
    @GetMapping("/places")
    public ResponseEntity<CustomApiResponse<PlaceRecommendationGetResponse>> recommendPlaces(
            @CurrentUserId Long userId,
            @RequestParam Long townId) {
        return CustomApiResponse.success(
                "장소 추천 조회 성공",
                recommendService.getRecommendPlaces(userId, townId)
        );
    }

    @Operation(summary = "자연어 기반 장소 추천", description = "사용자의 자연어 질문과 동네 ID를 기반으로 유사한 장소 상위 3개를 추천합니다.")
    @PostMapping("/places/embedding")
    public ResponseEntity<CustomApiResponse<EmbeddingPlaceRecommendGetResponse>> recommendPlacesByEmbedding(
            @CurrentUserId Long userId,
            @RequestBody @Valid EmbeddingPlaceRecommendRequest request) {
        return CustomApiResponse.success(
                "자연어 기반 장소 추천 조회 성공",
                embeddingRecommendService.recommendByQuery(request.query(), request.townId(), userId)
        );
    }

    @Operation(summary = "추천 코스 목록 조회", description = "특정 동네의 공유된 코스 목록을 조회합니다.")
    @GetMapping("/courses")
    public ResponseEntity<CustomApiResponse<CourseRecommendGetResponse>> findRecommendCourses(
            @CurrentUserId Long userId,
            @Parameter(description = "동네 ID", required = true)
            @RequestParam("townId")
            @NotNull(message = "동네 ID는 필수입니다")
            Long townId) {
        return CustomApiResponse.success(
                "추천 코스 목록 조회에 성공했습니다.",
                recommendService.getRecommendCourses(userId, townId)
        );
    }

}