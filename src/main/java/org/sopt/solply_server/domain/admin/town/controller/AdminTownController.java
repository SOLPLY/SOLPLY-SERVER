package org.sopt.solply_server.domain.admin.town.controller;

import org.sopt.solply_server.domain.admin.town.dto.request.AdminTownUpsertRequest;
import org.sopt.solply_server.domain.admin.town.dto.response.AdminTownListResponse;
import org.sopt.solply_server.domain.admin.town.dto.response.AdminTownUpsertResponse;
import org.sopt.solply_server.domain.admin.town.service.AdminTownService;
import org.sopt.solply_server.global.annotation.CurrentUserId;
import org.sopt.solply_server.global.dto.CustomApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "어드민(지역/동네 데이터 관리) API", description = "지역/동네 데이터 관리 Admin용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/towns")
public class AdminTownController {
	private final AdminTownService adminTownService;

	@Operation(summary = "어드민 지역/동네 생성", description = "어드민이 새 지역/동네를 생성합니다.")
	@PostMapping
	public ResponseEntity<CustomApiResponse<AdminTownUpsertResponse>> createTowns(
		@CurrentUserId Long adminUserId,
		@Valid @RequestBody AdminTownUpsertRequest request
	) {
		return CustomApiResponse.success(
			"지역/동네 생성 성공",
			adminTownService.createTown(adminUserId, request)
		);
	}

	@Operation(summary = "어드민 지역/동네 목록 조회", description = "어드민이 지역/동네 목록을 조회합니다.")
	@GetMapping
	public ResponseEntity<CustomApiResponse<AdminTownListResponse>> getTowns() {
		return CustomApiResponse.success(
			"지역/동네 목록 조회 성공",
			adminTownService.getTowns()
		);
	}
}
