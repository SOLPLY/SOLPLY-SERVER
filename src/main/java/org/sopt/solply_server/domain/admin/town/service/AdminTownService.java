package org.sopt.solply_server.domain.admin.town.service;

import java.util.List;

import org.sopt.solply_server.domain.admin.town.dto.AdminTownDto;
import org.sopt.solply_server.domain.admin.town.dto.request.AdminTownUpsertRequest;
import org.sopt.solply_server.domain.admin.town.dto.response.AdminTownListResponse;
import org.sopt.solply_server.domain.admin.town.dto.response.AdminTownUpsertResponse;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.town.repository.TownRepository;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTownService {

	private final TownRepository townRepository;
	private final EntityLoader entityLoader;

	private final TownValidator townValidator;

	@Transactional
	public AdminTownUpsertResponse createTown(final Long adminUserId, final AdminTownUpsertRequest req) {
		Town parent = (req.parentId() != null ? entityLoader.getTown(req.parentId()) : null);
		Town town = Town.create(
			req.name(),
			parent
		);
		Town saved = townRepository.save(town);

		log.info("어드민 동네 생성 - adminId: {}, parentId:{}", adminUserId, saved.getId());
		return AdminTownUpsertResponse.of(saved.getId());
	}

	@Transactional
	public AdminTownUpsertResponse updateTown(final Long townId, final AdminTownUpsertRequest req) {
		townValidator.validateTownId(townId);
		boolean isParent = townRepository.existsByIdAndParentIsNull(townId);

		Town town = entityLoader.getTown(townId);
		Town parent = isParent ? null : entityLoader.getTown(req.parentId());

		town.update(
			req.name(),
			parent
		);

		log.info("어드민 동네 수정 - parentId:{}", town.getId());
		return AdminTownUpsertResponse.of(town.getId());
	}

	public AdminTownListResponse getTowns() {
		List<Town> townList = townRepository.findAll();
		return AdminTownListResponse.of(
			townList.stream().map(town ->
				AdminTownDto.of(
					town.getId(),
					town.getName(),
					town.getParent() == null ? "-" : town.getParent().getName()
				)
			).toList()
		);
	}

	public void deleteTown(final Long townId) {
		Town town = entityLoader.getTown(townId);
		townValidator.validateDeletableTown(townId);
		townRepository.delete(town);
	}

	public AdminTownListResponse getParentsTowns() {
		List<Town> parentList = townRepository.findByParentIsNull();
		return AdminTownListResponse.of(
			parentList.stream().map(town ->
				AdminTownDto.of(
					town.getId(),
					town.getName(),
					"-"
				)
			).toList()
		);
	}
}
