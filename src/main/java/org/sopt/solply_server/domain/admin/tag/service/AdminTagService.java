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
    private final TagValidator tagValidator;
    private final EntityLoader entityLoader;

    @Transactional
    public Long createTag(AdminTagUpsertRequest req) {
        validateUpsert(req);

        Tag parent = req.parentId() == null ? null : entityLoader.getTag(req.parentId());

        Tag tag = Tag.create(
                req.name(),
                req.type(),
                parent,
                req.active()
        );

        if (req.personas() != null) {
            req.personas().forEach(p ->
                    tag.getPersonaMappings().add(
                            TagPersonaMapping.of(tag, p, 1)
                    )
            );
        }

        return tagRepository.save(tag).getId();
    }


    public AdminTagListResponse getTags() {
        return AdminTagListResponse.of(
                tagRepository.findAllWithParent().stream()
                        .map(AdminTagSummaryDto::from)
                        .toList()
        );
    }

    public AdminTagDetailsResponse getTagDetails(Long id) {
        Tag tag = tagRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_TAG));

        return AdminTagDetailsResponse.from(tag);
    }

    @Transactional
    public Long updateTag(Long id, AdminTagUpsertRequest req) {
        Tag tag = entityLoader.getTag(id);
        validateUpsert(req);

        Tag parent = req.parentId() == null ? null : entityLoader.getTag(req.parentId());

        tag.updateBasic(req.type(), parent, req.name(), req.active());

        tag.getPersonaMappings().clear();
        if (req.personas() != null) {
            req.personas().forEach(p ->
                    tag.getPersonaMappings().add(
                            TagPersonaMapping.of(tag, p, 1)
                    )
            );
        }

        if (!req.active()) {
            deactivateCascade(tag.getId());
        }

        return id;
    }

    /**
     * 태그 활성/비활성 토글
     * - 비활성화 시 하위 태그들도 같이 비활성화
     */
    @Transactional
    public AdminTagToggleResponse toggleActive(Long id, boolean active) {
        Tag tag = entityLoader.getTag(id);
        tag.setActive(active);

        if (!active) {
            deactivateCascade(tag.getId());
        }

        return AdminTagToggleResponse.of(id, active);
    }

    // ===== private =====

    private void validateUpsert(AdminTagUpsertRequest req) {
        if (req.type() == TagType.MAIN) {
            if (req.parentId() != null) {
                throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
            }
            return;
        }

        // OPTION1/2
        if (req.parentId() == null) {
            throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
        }

        // parent는 MAIN이어야 함 (존재+타입 검증)
        tagValidator.validateTagType(req.parentId(), TagType.MAIN);

        // parent가 active인지까지 보려면
        Tag parent = entityLoader.getTag(req.parentId());
        if (!parent.isActive()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
        }
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