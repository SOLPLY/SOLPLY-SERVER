package org.sopt.solply_server.domain.admin.town.util;

import java.util.List;

import org.sopt.solply_server.domain.admin.place.util.AdminPlaceValidator;
import org.sopt.solply_server.domain.admin.town.repository.AdminTownRepository;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminTownValidator {

	private final AdminTownRepository adminTownRepository;
	private final TownValidator townValidator;

	private final AdminPlaceValidator adminPlaceValidator;

	public void validateTownId(Long townId) {
		townValidator.validateTownId(townId);
	}

	public void validateParentTown(Long townId) {
		if (!adminTownRepository.existsByIdAndParentIsNull(townId)) {
			throw new BusinessException(ErrorCode.NOT_PARENT_TOWN);
		}
	}

	public void validateChildTown(Long townId) {
		if (adminTownRepository.existsByIdAndParentIsNull(townId)) {
			throw new BusinessException(ErrorCode.NOT_CHILD_TOWN);
		}
	}

	public void validateDeletableTown(Long townId) {
		if (adminPlaceValidator.validatePlaceExistsByTownId(townId)) {
			throw new BusinessException(ErrorCode.CANNOT_DELETE_TOWN);
		}
	}


	public void validateDeactivatableParentTown(Long townId) {
		List<Long> townIdList = adminTownRepository.findIdsByParent_Id(townId);
		for (Long id : townIdList) {
			if (adminPlaceValidator.validatePlaceExistsByTownId(id)) {
				throw new BusinessException(ErrorCode.CANNOT_DEACTIVATE_TOWN);
			}
		}
	}

	public void validateDeactivatableChildTown(Long townId) {
		if (adminPlaceValidator.validatePlaceExistsByTownId(townId)) {
			throw new BusinessException(ErrorCode.CANNOT_DEACTIVATE_TOWN);
		}
	}
}
