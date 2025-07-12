package org.sopt.solply_server.domain.course.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.course.service.CourseService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "코스 API", description = "코스 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses")
@Validated
public class CourseController {

    private final CourseService courseService;

    @Operation(summary = "코스 상세 조회", description = "코스 ID를 통해 코스의 상세 정보를 조회합니다.")
    @GetMapping("/{courseId}")
    public ResponseEntity<CustomApiResponse<CourseDetailGetResponse>> findCourseDetailsById(
            @CurrentUserId Long userId,
            @PathVariable Long courseId) {
        return CustomApiResponse.success(
                "코스 상세 조회에 성공했습니다.",
                courseService.findCourseDetailsById(userId, courseId)
        );
    }

    @Operation(summary = "추천 코스 목록 조회", description = "특정 동네의 공유된 코스 목록을 조회합니다.")
    @GetMapping("/recommend")
    public ResponseEntity<CustomApiResponse<CourseRecommendGetResponse>> findRecommendCourses(
            @CurrentUserId Long userId,
            @Parameter(description = "동네 ID", required = true)
            @RequestParam("townId")
            @NotNull(message = "동네 ID는 필수입니다")
            Long townId) {
        return CustomApiResponse.success(
                "추천 코스 목록 조회에 성공했습니다.",
                courseService.findRecommendCourses(userId, townId)
        );
    }
}