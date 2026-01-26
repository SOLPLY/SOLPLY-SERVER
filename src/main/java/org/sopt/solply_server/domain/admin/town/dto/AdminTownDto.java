package org.sopt.solply_server.domain.admin.town.dto;

public record AdminTownDto(
	Long id,
	String townName,
	String parentName
) {
	public static AdminTownDto of(Long id, String townName, String parentName) {
		return new AdminTownDto(id, townName, parentName);
	}
}
