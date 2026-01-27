package org.sopt.solply_server.domain.admin.place.util;

import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminPlaceValidator {

	private final PlaceRepository placeRepository;

	public boolean validatePlaceExistsByTownId(Long townId) {
		return placeRepository.existsByTown_Id(townId);
	}
}
