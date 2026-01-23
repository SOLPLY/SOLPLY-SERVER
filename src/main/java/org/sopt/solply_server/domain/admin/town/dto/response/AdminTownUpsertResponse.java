package org.sopt.solply_server.domain.admin.town.dto.response;

public record AdminTownUpsertResponse(Long townId) {
	public static AdminTownUpsertResponse of(Long townId) { return new AdminTownUpsertResponse(townId); }
}
