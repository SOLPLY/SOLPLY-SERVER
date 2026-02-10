package org.sopt.solply_server.domain.admin.course.controller;

import org.sopt.solply_server.domain.admin.course.dto.request.AdminCourseUpsertRequest;
import org.sopt.solply_server.domain.admin.course.dto.response.AdminCourseUpsertResponse;
import org.sopt.solply_server.domain.admin.course.service.AdminCourseService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "어드민(코스 데이터 관리) API", description = "코스 데이터 관리 Admin용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/courses")
public class AdminCourseController {
	private final AdminCourseService adminCourseService;

	@Operation(summary = "어드민 코스 생성", description = "어드민이 새 코스를 생성합니다.")
	@PostMapping
	public ResponseEntity<CustomApiResponse<AdminCourseUpsertResponse>> createCourse(
		@CurrentUserId Long adminUserId,
		@Valid @RequestBody AdminCourseUpsertRequest request
	) {
		return CustomApiResponse.success(
			"어드민 생성 성공",
			adminCourseService.createCourse(adminUserId, request)
		);
	}
}
