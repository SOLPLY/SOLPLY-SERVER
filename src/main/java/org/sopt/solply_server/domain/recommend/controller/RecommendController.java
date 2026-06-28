package org.sopt.solply_server.domain.recommend.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.sopt.solply_server.domain.recommend.dto.EmbeddingCourseRecommendRequest;
import org.sopt.solply_server.domain.recommend.dto.EmbeddingPlaceRecommendRequest;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.recommend.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.recommend.dto.response.EmbeddingCourseRecommendGetResponse;
import org.sopt.solply_server.domain.recommend.dto.response.EmbeddingPlaceRecommendGetResponse;
import org.sopt.solply_server.domain.recommend.dto.response.ExamplePhrasesGetResponse;
import org.sopt.solply_server.domain.recommend.dto.response.PlaceRecommendationGetResponse;
import org.sopt.solply_server.domain.recommend.entity.RecommendTargetType;
import org.sopt.solply_server.domain.recommend.service.CourseEmbeddingRecommendService;
import org.sopt.solply_server.domain.recommend.service.EmbeddingRecommendService;
import org.sopt.solply_server.domain.recommend.service.RecommendExamplePhraseService;
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
    private final CourseEmbeddingRecommendService courseEmbeddingRecommendService;
    private final RecommendExamplePhraseService recommendExamplePhraseService;

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

    @Operation(summary = "자연어 기반 코스 추천", description = "사용자의 자연어 질문과 동네 ID를 기반으로 유사한 공유 코스 상위 3개를 추천합니다.")
    @PostMapping("/courses/embedding")
    public ResponseEntity<CustomApiResponse<EmbeddingCourseRecommendGetResponse>> recommendCoursesByEmbedding(
            @CurrentUserId Long userId,
            @RequestBody @Valid EmbeddingCourseRecommendRequest request) {
        return CustomApiResponse.success(
                "자연어 기반 코스 추천 조회 성공",
                courseEmbeddingRecommendService.recommendByQuery(request.query(), request.townId(), userId)
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

    @Operation(summary = "추천 예시 문구 조회",
            description = "자연어 추천 페이지에서 보여줄 타입별(PLACE/COURSE) 예시 문구를 조회합니다.")
    @GetMapping("/example-phrases")
    public ResponseEntity<CustomApiResponse<ExamplePhrasesGetResponse>> getExamplePhrases(
            @Parameter(description = "추천 타입 (PLACE / COURSE)", required = true)
            @RequestParam("type")
            RecommendTargetType type) {
        return CustomApiResponse.success(
                "추천 예시 문구 조회 성공",
                recommendExamplePhraseService.getExamplePhrases(type)
        );
    }

}