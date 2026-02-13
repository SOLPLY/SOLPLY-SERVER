package org.sopt.solply_server.domain.admin.course.dto.request;

import java.util.List;

import org.sopt.solply_server.domain.admin.course.dto.AdminCoursePlaceInfoDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminCourseUpsertRequest(
	@NotBlank(message = "코스명은 필수입니다.")
	@Size(max = 100, message = "코스명은 100자를 초과할 수 없습니다.")
	String name,

	@NotBlank(message = "코스 설명은 필수입니다.")
	@Size(max = 100, message = "코스 설명은 100자를 초과할 수 없습니다.")
	String intro,

	@NotNull(message = "townId는 필수입니다.")
	Long townId,

	@NotNull(message = "코스 구성 장소는 필수입니다.")
	@Size(min = 2, max = 6)
	@Valid
	List<AdminCoursePlaceInfoDto> placeList,

	@NotNull
	Long tagId
) {
}
