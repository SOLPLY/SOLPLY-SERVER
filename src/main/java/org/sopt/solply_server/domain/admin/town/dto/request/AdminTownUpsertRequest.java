package org.sopt.solply_server.domain.admin.town.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminTownUpsertRequest(
	@NotBlank(message = "동네명은 필수입니다.")
	@Size(max = 100, message = "동네명은 100자를 초과할 수 없습니다.")
	String name,

	@NotNull(message = "townId는 필수입니다.")
	Long townId
) {}
