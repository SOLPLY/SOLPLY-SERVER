package org.sopt.solply_server.domain.admin.tag.service;


import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagActivationRequest;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagActivationResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagDetailsResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagListResponse;
import org.sopt.solply_server.domain.admin.tag.repository.AdminTagRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagPersonaMapping;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTagService {

    private final AdminTagRepository adminTagRepository;
    private final EntityLoader entityLoader;

    private final AdminTagValidator adminTagValidator;

    @Transactional
    public Long createTag(AdminTagUpsertRequest req) {

        Tag parent = null;
        if (req.parentId() != null) {
            parent = entityLoader.getTag(req.parentId()); // admin은 active 무시
        }

        adminTagValidator.validateParentForAdmin(
                req.type(),
                req.parentId(),
                req.active(),
                parent
        );

        Tag tag = Tag.create(
                req.name(),
                req.type(),
                parent,
                req.active()
        );

        if (req.personas() != null) {
            req.personas().forEach(p ->
                    tag.getPersonaMappings().add(TagPersonaMapping.of(tag, p, 1))
            );
        }

        return adminTagRepository.save(tag).getId();
    }

    @Transactional(readOnly = true)
    public AdminTagListResponse getTags() {
        return AdminTagListResponse.of(
                adminTagRepository.findAllWithParent().stream()
                        .map(AdminTagListResponse.AdminTagSummaryDto::from)
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public AdminTagDetailsResponse getTagDetails(Long id) {
        Tag tag = adminTagRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_TAG));

        return AdminTagDetailsResponse.from(tag);
    }

    @Transactional
    public Long updateTag(Long id, AdminTagUpsertRequest req) {
        Tag tag = entityLoader.getTag(id);

        Tag parent = null;
        if (req.parentId() != null) {
            parent = entityLoader.getTag(req.parentId());
        }

        adminTagValidator.validateParentForAdmin(
                req.type(),
                req.parentId(),
                req.active(),
                parent
        );

        tag.updateBasic(req.type(), parent, req.name(), req.active());

        tag.getPersonaMappings().clear();
        if (req.personas() != null) {
            req.personas().forEach(p ->
                    tag.getPersonaMappings().add(TagPersonaMapping.of(tag, p, 1))
            );
        }

        if (!req.active()) {
            deactivateCascade(tag.getId());
        }

        return id;
    }

    @Transactional
    public AdminTagActivationResponse toggleActive(Long id, AdminTagActivationRequest req) {
        Tag tag = entityLoader.getTag(id);

        // 활성화 요청인데 parent가 비활성이면 에러
        if (req.active() && tag.getParent() != null && !tag.getParent().isActive()) {
            throw new BusinessException(ErrorCode.CANNOT_ACTIVATE_TAG_PARENT_INACTIVE);
        }

        tag.setActive(req.active());

        if (!req.active()) {
            deactivateCascade(tag.getId());
        }

        return AdminTagActivationResponse.of(id, req.active());
    }


    private void deactivateCascade(Long parentId) {
        List<Tag> children = adminTagRepository.findChildren(parentId);
        for (Tag child : children) {
            if (child.isActive()) {
                child.setActive(false);
                deactivateCascade(child.getId());
            }
        }
    }
}