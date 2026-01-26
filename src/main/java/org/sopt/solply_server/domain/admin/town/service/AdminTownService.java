package org.sopt.solply_server.domain.admin.town.service;

import java.util.List;

import org.sopt.solply_server.domain.admin.town.dto.AdminTownDto;
import org.sopt.solply_server.domain.admin.town.dto.request.AdminTownUpsertRequest;
import org.sopt.solply_server.domain.admin.town.dto.request.AdminTownActivationRequest;
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
		Town parent = null;
		if (req.parentId() != null) {
			townValidator.validateTownId(req.parentId());
			parent = entityLoader.getTown(req.parentId());
		}

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
		townValidator.validateActivatableTown(townId, req.parentId());
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
		List<AdminTownDto> townDtoList =
			townList.stream().map(town ->
				AdminTownDto.of(
					town.getId(),
					town.getName(),
					town.getParent() == null ? "-" : town.getParent().getName()
				)
			).toList();

		log.info("어드민 지역/동네 리스트 조회 성공");
		return AdminTownListResponse.of(townDtoList);
	}

	public void deleteTown(final Long townId) {
		Town town = entityLoader.getTown(townId);
		townValidator.validateDeletableTown(townId);
		townRepository.delete(town);

		log.info("어드민 지역 삭제 성공");
	}

	public AdminTownListResponse getParentsTowns() {
		List<AdminTownDto> parentList = townRepository.findByParentIsNull()
			.stream().map(town ->
				AdminTownDto.of(
					town.getId(),
					town.getName(),
					"-"
				)
			).toList();

		log.info("어드민 지역 목록 조회 성공");
		return AdminTownListResponse.of(parentList);
	}

	@Transactional
	public AdminTownUpsertResponse updateTownStatus(final Long townId, final AdminTownActivationRequest req) {
		townValidator.validateTownId(townId);
		Town town = entityLoader.getTown(townId);

		town.updateActivation(req.active());

		log.info("어드민 지역/동네 활성화 수정 - townId: {}", town.getId());
		return AdminTownUpsertResponse.of(town.getId());
	}
}
