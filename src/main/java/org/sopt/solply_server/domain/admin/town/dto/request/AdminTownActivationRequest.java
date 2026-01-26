package org.sopt.solply_server.domain.admin.town.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminTownActivationRequest(
	@NotBlank(message = "업데이트할 상태는 필수입니다.")
	boolean active
) {
}
