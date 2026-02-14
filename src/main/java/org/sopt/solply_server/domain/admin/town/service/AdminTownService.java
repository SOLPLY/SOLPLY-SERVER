package org.sopt.solply_server.domain.admin.town.service;

import java.util.ArrayList;
import java.util.List;

import org.sopt.solply_server.domain.admin.place.service.AdminPlaceService;
import org.sopt.solply_server.domain.admin.town.dto.AdminTownDto;
import org.sopt.solply_server.domain.admin.town.dto.request.AdminTownActivationRequest;
import org.sopt.solply_server.domain.admin.town.dto.request.AdminTownUpsertRequest;
import org.sopt.solply_server.domain.admin.town.dto.response.AdminTownListResponse;
import org.sopt.solply_server.domain.admin.town.dto.response.AdminTownUpsertResponse;
import org.sopt.solply_server.domain.admin.town.repository.AdminTownRepository;
import org.sopt.solply_server.domain.admin.town.util.AdminTownValidator;
import org.sopt.solply_server.domain.town.entity.Town;
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

	private final AdminTownRepository adminTownRepository;
	private final EntityLoader entityLoader;

	private final AdminTownValidator adminTownValidator;
	private final AdminPlaceService adminPlaceService;

	@Transactional
	public AdminTownUpsertResponse createTown(final Long adminUserId, final AdminTownUpsertRequest req) {
		Town parent = null;
		if (req.parentId() != null) {
			adminTownValidator.validateTownId(req.parentId());
			parent = entityLoader.getTown(req.parentId());
		}

		Town town = Town.create(
			req.name(),
			parent,
			true
		);
		Town saved = adminTownRepository.save(town);

		log.info("어드민 동네 생성 - adminId: {}, townId:{}", adminUserId, saved.getId());
		return AdminTownUpsertResponse.of(saved.getId());
	}

	@Transactional
	public AdminTownUpsertResponse updateTown(final Long townId, final AdminTownUpsertRequest req) {
		Town town = entityLoader.getTown(townId);

		// 부모 town, 자식 town 구분
		Town parent;
		if (req.parentId() == null) {
			adminTownValidator.validateParentTown(townId);
			parent = null;
		} else {
			adminTownValidator.validateChildTown(townId);
			adminTownValidator.validateParentTown(req.parentId());
			parent = entityLoader.getTown(req.parentId());
		}

		town.update(
			req.name(),
			parent
		);

		log.info("어드민 동네 수정 - parentId:{}", town.getId());
		return AdminTownUpsertResponse.of(town.getId());
	}

	public AdminTownListResponse getTowns() {
		List<Town> townList = adminTownRepository.findAll();
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

	@Transactional
	public void deleteTown(final Long townId) {
		Town town = entityLoader.getTown(townId);
		adminTownValidator.validateDeletableTown(townId);
		adminTownRepository.delete(town);

		log.info("어드민 지역 삭제 성공");
	}

	public AdminTownListResponse getParentsTowns() {
		List<AdminTownDto> parentList = adminTownRepository.findByParentIsNull()
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

	// 활성화: 전파 && 비활성화: 전파X
	@Transactional
	public AdminTownUpsertResponse updateTownStatus(final Long townId, final AdminTownActivationRequest req) {
		adminTownValidator.validateTownId(townId);

		if (req.active()) {
			activateTown(townId);
		} else {
			deactivateTown(townId);
		}

		log.info("어드민 지역/동네 활성화 수정 - townId: {}", townId);
		return AdminTownUpsertResponse.of(townId);
	}

	private void activateTown(Long townId) {
		Town town = entityLoader.getTown(townId);
		List<Long> townIds = new ArrayList<>();

		if (town.getParent() == null) {
			townIds.addAll(adminTownRepository.findIdsByParent_Id(townId));
		}
		townIds.add(townId);

		adminPlaceService.activatePlacesByTownIds(townIds);
		int cnt = adminTownRepository.updateActiveByTownIds(townIds, true);

		log.info("어드민 지역/동네 활성화된 동네 갯수: {}", cnt);
	}

	private void deactivateTown(Long townId) {
		Town town = entityLoader.getTown(townId);

		if (town.getParent() == null) {
			adminTownValidator.validateDeactivatableParentTown(townId);
		} else {
			adminTownValidator.validateDeactivatableChildTown(townId);
		}
		town.updateActivation(false);
	}
}
