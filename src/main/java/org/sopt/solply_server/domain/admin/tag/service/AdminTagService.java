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
        boolean active = normalizeActiveByParent(req);

        Tag parent = req.parentId() == null ? null : entityLoader.getTag(req.parentId());

        Tag tag = Tag.create(
                req.name(),
                req.type(),
                parent,
                active
        );

        if (req.personas() != null) {
            req.personas().forEach(p ->
                    tag.getPersonaMappings().add(TagPersonaMapping.of(tag, p, 1))
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

        boolean active = normalizeActiveByParent(req);
        Tag parent = req.parentId() == null ? null : entityLoader.getTag(req.parentId());

        tag.updateBasic(req.type(), parent, req.name(), active);

        tag.getPersonaMappings().clear();
        if (req.personas() != null) {
            req.personas().forEach(p ->
                    tag.getPersonaMappings().add(TagPersonaMapping.of(tag, p, 1))
            );
        }

        if (!active) {
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

        // 활성화 요청인데, parent가 비활성이면 불가 -> 에러
        if (active && tag.getParent() != null && !tag.getParent().isActive()) {
            throw new BusinessException(ErrorCode.CANNOT_ACTIVATE_TAG_PARENT_INACTIVE);
        }

        tag.setActive(active);

        // 비활성화면 하위까지 같이 끔
        if (!active) {
            deactivateCascade(tag.getId());
        }

        return AdminTagToggleResponse.of(id, active);
    }

    // ===== private =====

    private void deactivateCascade(Long parentId) {
        List<Tag> children = tagRepository.findChildren(parentId);
        for (Tag child : children) {
            if (child.isActive()) {
                child.setActive(false);
                deactivateCascade(child.getId());
            }
        }
    }

    private boolean normalizeActiveByParent(AdminTagUpsertRequest req) {
        // MAIN은 parent 없음. 요청값 그대로 사용
        if (req.type() == TagType.MAIN) {
            if (req.parentId() != null) {
                throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
            }
            return req.active();
        }

        // OPTION1/2는 parent 필수
        if (req.parentId() == null) {
            throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
        }

        // parent는 MAIN이어야 함(존재 + 타입)
        tagValidator.validateTagType(req.parentId(), TagType.MAIN);

        Tag parent = entityLoader.getTag(req.parentId());

        // parent가 비활성이라면 자식은 무조건 비활성로 강제
        if (!parent.isActive()) {
            return false;
        }

        return req.active();
    }
}