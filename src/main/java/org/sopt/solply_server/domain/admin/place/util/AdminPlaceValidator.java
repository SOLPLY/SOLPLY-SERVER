package org.sopt.solply_server.domain.admin.place.util;

import java.util.List;

import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminPlaceValidator {

	private final AdminPlaceRepository adminPlaceRepository;

	public boolean validatePlaceExistsByTownId(Long townId) {
		return adminPlaceRepository.existsByTown_Id(townId);
	}

	public boolean validatePlaceExistsByTownIds(List<Long> townIds) {
		return adminPlaceRepository.exisitsPlacesByTown_Ids(townIds);
	}
}
