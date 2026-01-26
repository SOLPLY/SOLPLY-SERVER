package org.sopt.solply_server.domain.admin.tag.service;


import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagDetailsResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagListResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagListResponse.AdminTagSummaryDto;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagToggleResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagPersonaMapping;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.domain.tag.util.TagValidator;
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

    private final TagRepository tagRepository;
    private final EntityLoader entityLoader;

    @Transactional
    public Long createTag(AdminTagUpsertRequest req) {
        Tag parent = resolveAndValidateParentForAdmin(req.type(), req.parentId(), req.active());

        Tag tag = Tag.create(
                req.name(),
                req.type(),
                parent,
                req.active()
        );

        if (req.personas() != null) {
            req.personas().forEach(p -> tag.getPersonaMappings().add(TagPersonaMapping.of(tag, p, 1)));
        }

        return tagRepository.save(tag).getId();
    }

    @Transactional
    public Long updateTag(Long id, AdminTagUpsertRequest req) {
        Tag tag = entityLoader.getTag(id);

        Tag parent = resolveAndValidateParentForAdmin(req.type(), req.parentId(), req.active());

        tag.updateBasic(req.type(), parent, req.name(), req.active());

        tag.getPersonaMappings().clear();
        if (req.personas() != null) {
            req.personas().forEach(p -> tag.getPersonaMappings().add(TagPersonaMapping.of(tag, p, 1)));
        }

        // 비활성화면 하위까지
        if (!req.active()) {
            deactivateCascade(tag.getId());
        }

        return id;
    }

    @Transactional
    public AdminTagToggleResponse toggleActive(Long id, boolean active) {
        Tag tag = entityLoader.getTag(id);

        // 활성화 요청인데 parent가 비활성이면 에러
        if (active && tag.getParent() != null && !tag.getParent().isActive()) {
            throw new BusinessException(ErrorCode.CANNOT_ACTIVATE_TAG_PARENT_INACTIVE);
        }

        tag.setActive(active);

        if (!active) {
            deactivateCascade(tag.getId());
        }

        return AdminTagToggleResponse.of(id, active);
    }

    // ===== admin 전용 parent 검증 =====
    private Tag resolveAndValidateParentForAdmin(TagType type, Long parentId, boolean requestedActive) {
        if (type == TagType.MAIN) {
            if (parentId != null) throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
            return null;
        }

        // OPTION1/2는 parent 필수
        if (parentId == null) throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);

        // parent 존재 + MAIN 타입 검증 (active는 보지 않음)
        if (!tagRepository.existsByIdAndType(parentId, TagType.MAIN)) {
            throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
        }

        Tag parent = entityLoader.getTag(parentId);

        // parent 비활성인데 자식을 활성화하려고 하면 에러
        if (requestedActive && !parent.isActive()) {
            throw new BusinessException(ErrorCode.CANNOT_ACTIVATE_TAG_PARENT_INACTIVE);
        }

        return parent;
    }

    private void deactivateCascade(Long parentId) {
        List<Tag> children = tagRepository.findChildren(parentId);
        for (Tag child : children) {
            if (child.isActive()) {
                child.setActive(false);
                deactivateCascade(child.getId());
            }
        }
    }
}