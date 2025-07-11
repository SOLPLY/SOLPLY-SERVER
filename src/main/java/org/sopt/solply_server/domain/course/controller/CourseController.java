package org.sopt.solply_server.domain.course.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.service.CourseService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "코스 API", description = "코스 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses")
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
}