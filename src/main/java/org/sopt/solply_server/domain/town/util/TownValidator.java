package org.sopt.solply_server.domain.town.util;

import lombok.RequiredArgsConstructor;

import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TownValidator {

	private final TownRepository townRepository;
	private final PlaceRepository placeRepository;

	public void validateTownId(Long townId) {
		if (!townRepository.existsById(townId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND_TOWN);
		}
	}

	public void validateDeletableTown(Long townId) {
		if (placeRepository.existsByTown_Id(townId)) {
			throw new BusinessException(ErrorCode.CANNOT_DELETE_TOWN);
		}
	}

	public void validateParentTown(Long townId) {
		if (!townRepository.existsByIdAndParentIsNull(townId)) {
			throw new BusinessException(ErrorCode.NOT_PARENT_TOWN);
		}
	}

	public void validateChildTown(Long townId) {
		if (townRepository.existsByIdAndParentIsNull(townId)) {
			throw new BusinessException(ErrorCode.NOT_CHILD_TOWN);
		}
	}
}