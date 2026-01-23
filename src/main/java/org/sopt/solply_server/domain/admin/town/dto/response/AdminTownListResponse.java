package org.sopt.solply_server.domain.admin.town.dto.response;

import java.util.List;

import org.sopt.solply_server.domain.admin.town.dto.AdminTownDto;

public record AdminTownListResponse(List<AdminTownDto>towns) {
	public static AdminTownListResponse of(List<AdminTownDto> towns) {
		return new AdminTownListResponse(towns);
	}
}
