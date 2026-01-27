package org.sopt.solply_server.domain.town.util;

import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TownValidator {

	private final TownRepository townRepository;

	public void validateTownId(Long townId) {
		if (!townRepository.existsById(townId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND_TOWN);
		}
	}
}