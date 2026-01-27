package org.sopt.solply_server.domain.admin.place.util;

import org.sopt.solply_server.domain.admin.place.repository.AdminPlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminPlaceValidator {

	private final AdminPlaceRepository adminPlaceRepository;

	public boolean validatePlaceExistsByTownId(Long townId) {
		return adminPlaceRepository.existsByTown_Id(townId);
	}
}
