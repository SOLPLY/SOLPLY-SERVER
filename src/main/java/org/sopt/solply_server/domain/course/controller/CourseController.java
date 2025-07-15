package org.sopt.solply_server.domain.course.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.dto.request.CourseCreateRequest;
import org.sopt.solply_server.domain.course.dto.response.CourseCreateResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.dto.response.CourseFolderPreviewListGetResponse;
import org.sopt.solply_server.domain.course.dto.request.PlaceAddToCourseRequest;
import org.sopt.solply_server.domain.course.dto.response.*;
import org.sopt.solply_server.domain.course.service.CourseBookmarkService;
import org.sopt.solply_server.domain.course.service.CourseService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Tag(name = "코스 API", description = "코스 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses")
@Validated
public class CourseController {

    private final CourseService courseService;
    private final CourseBookmarkService courseBookmarkService;

    @Operation(summary = "코스 생성", description = "새로운 코스를 생성합니다.")
    @PostMapping
    public ResponseEntity<CustomApiResponse<CourseCreateResponse>> createCourse(
            @CurrentUserId Long userId,
            @Valid @RequestBody CourseCreateRequest request) {
        return CustomApiResponse.success(
                "코스 생성을 완료했습니다.",
                courseService.createCourse(userId, request)
        );
    }

    @Operation(summary = "코스에 장소 추가", description = "기존 코스에 새로운 장소를 마지막 순서로 추가합니다.")
    @PostMapping("/{courseId}/places")
    public ResponseEntity<CustomApiResponse<Void>> addPlaceToCourse(
            @CurrentUserId Long userId,
            @Parameter(description = "코스 ID", required = true)
            @PathVariable("courseId") Long courseId,
            @Valid @RequestBody PlaceAddToCourseRequest request) {
        courseService.addPlaceToCourse(userId, courseId, request);
        return CustomApiResponse.success("해당 코스에 장소가 성공적으로 추가되었습니다.");
    }

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

    @Operation(summary = "사용자 북마크 코스 목록 조회",
            description = "사용자가 추가 또는 조회할 수 있는 북마크한 코스 목록을 조회합니다.")
    @GetMapping("/bookmarks")
    public ResponseEntity<CustomApiResponse<CourseBookmarkListGetResponse>> getBookmarkedCourses(
            @CurrentUserId Long userId,
            @Parameter(description = "동네 ID", required = true)
            @RequestParam("townId")
            @NotNull(message = "동네 ID는 필수입니다")
            Long townId,
            @Parameter(description = "장소 ID (선택사항, 해당 장소를 추가할 수 있는 코스만 필터링)")
            @RequestParam(value = "placeId", required = false)
            Long placeId) {
        return CustomApiResponse.success(
                "사용자 코스 목록 조회에 성공했습니다.",
                courseService.getBookmarkedCourses(userId, townId, placeId)
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

    @Operation(summary = "코스 북마크 저장", description = "코스를 북마크에 등록합니다.")
    @PostMapping("/{courseId}/bookmarks")
    public ResponseEntity<CustomApiResponse<Void>> bookmarkCourse(
            @CurrentUserId Long userId,
            @Parameter(description = "코스 ID", required = true)
            @PathVariable("courseId") Long courseId) {
        courseBookmarkService.createCourseBookmark(userId, courseId);
        return CustomApiResponse.success("코스를 수집함에 저장했습니다.");
    }

    @Operation(summary = "코스 북마크 삭제", description = "코스 북마크를 삭제합니다.")
    @DeleteMapping("/{courseId}/bookmarks")
    public ResponseEntity<CustomApiResponse<Void>> deleteBookmarkCourse(
            @CurrentUserId Long userId,
            @Parameter(description = "코스 ID", required = true)
            @PathVariable("courseId") Long courseId) {
        courseBookmarkService.deleteCourseBookmark(userId, courseId);
        return CustomApiResponse.success("코스를 수집함에서 삭제했습니다.");
    }

    @Operation(summary = "선택한 코스 북마크 리스트 삭제", description = "여러 코스 북마크를 한번에 삭제합니다.")
    @DeleteMapping("/bookmarks")
    public ResponseEntity<CustomApiResponse<Void>> deleteBookmarkCourses(
            @CurrentUserId Long userId,
            @Parameter(description = "삭제할 코스 ID 목록", required = true)
            @RequestParam("courseIds")
            @NotNull(message = "courseIds는 null 혹은 비어있을 수 없습니다")
            List<Long> courseIds) {
        courseBookmarkService.deleteCourseBookmarks(userId, courseIds);
        return CustomApiResponse.success("선택한 코스를 수집함에서 삭제했습니다.");
    }

    @Operation(summary = "코스 북마크 폴더 프리뷰 조회", description = "동네별로 가장 최근에 북마크한 코스의 프리뷰를 조회합니다.")
    @GetMapping("/bookmarks/folders")
    public ResponseEntity<CustomApiResponse<CourseFolderPreviewListGetResponse>> getBookmarkedCourseFolderPreview(
            @CurrentUserId Long userId) {
        return CustomApiResponse.success(
                "나만의 코스 프리뷰 조회에 성공했습니다.",
                courseService.getBookmarkedCourseFolderPreview(userId)
        );
    }
}