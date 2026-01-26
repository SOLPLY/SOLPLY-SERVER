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

	public void validateParentOrSubTown(Long townId, Long parentId) {
		boolean isParentTown = townRepository.existsByIdAndParentIsNull(townId);
		boolean haveParentId = parentId != null;

		if ((isParentTown && haveParentId)
			|| (!isParentTown && !haveParentId)) {
			throw new BusinessException(ErrorCode.CANNOT_UPDATE_TOWN);
		}
	}
}