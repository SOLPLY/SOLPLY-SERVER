package org.sopt.solply_server.domain.admin.tag.service;


import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagActivationRequest;
import org.sopt.solply_server.domain.admin.tag.dto.request.AdminTagUpsertRequest;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagActivationResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagDetailsResponse;
import org.sopt.solply_server.domain.admin.tag.dto.response.AdminTagListResponse;
import org.sopt.solply_server.domain.admin.tag.repository.AdminTagRepository;
import org.sopt.solply_server.domain.admin.tag.util.AdminTagValidator;
import org.sopt.solply_server.domain.place.util.TagBitmask;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTagService {

    private final AdminTagRepository adminTagRepository;
    private final AdminEntityLoader adminEntityLoader;
    private final AdminTagValidator adminTagValidator;
    private final EntityManager entityManager;

    /**
     * <b>태그 id는 {@code place_stats.tag_bitmask}의 비트 자리다</b> — 62를 넘는 id가 생기면 목록
     * 조회의 태그 필터가 성립하지 않는다 ({@code TagBitmask}). 그래서 여기서 거부한다.
     *
     * <p><b>저장한 뒤에 판정하고 롤백하는 것이 의도다.</b> id가 auto-increment라 저장 전에는 어떤
     * 값을 받을지 알 수 없고, {@code MAX(id) + 1}로 예측하면 삭제된 id·동시 생성에서 어긋난다.
     * 실제로 받은 값을 보고 트랜잭션째 되돌리는 것이 유일하게 정확한 판정이다. auto-increment
     * 카운터는 롤백해도 되돌아가지 않지만, 그것은 이 가드가 이미 "더 못 만든다"를 확정한 뒤의 일이라
     * 무해하다.
     *
     * <p>여기서 막히면 <b>스키마 결정이 필요하다</b>는 뜻이다 — 마스크를 넓히거나(BINARY(n)) 태그
     * 소속을 다시 조인으로 되돌리거나. 우회로를 코드에 두지 말 것.
     */
    @Transactional
    public Long createTag(AdminTagUpsertRequest req) {

        Tag parent = null;
        if (req.parentId() != null) {
            parent = adminEntityLoader.getTag(req.parentId()); // admin은 active 무시
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
                req.active(),
                req.usage()
        );

        // 하드코딩: 가중치
        tag.replacePersonaMappings(req.personas(), 1);

        Long tagId = adminTagRepository.save(tag).getId();
        if (tagId > TagBitmask.MAX_TAG_ID) {
            throw new BusinessException(ErrorCode.TAG_ID_BIT_LIMIT_EXCEEDED);
        }
        return tagId;
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
        Tag tag = adminEntityLoader.getTag(id);

        Tag parent = null;
        if (req.parentId() != null) {
            parent = adminEntityLoader.getTag(req.parentId());
        }

        adminTagValidator.validateParentForAdmin(
                req.type(),
                req.parentId(),
                req.active(),
                parent
        );

        tag.updateBasic(req.type(), parent, req.name(), req.active(), req.usage());

        tag.clearPersonaMappings();
        entityManager.flush();

        // 하드코딩: 가중치
        tag.replacePersonaMappings(req.personas(), 1);

        if (!req.active()) {
            deactivateCascade(tag.getId());
        }

        return id;
    }

    @Transactional
    public AdminTagActivationResponse toggleActive(Long id, AdminTagActivationRequest req) {
        Tag tag = adminEntityLoader.getTag(id);

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


    public List<Long> collectSubtreeIds(Long rootId) {
        List<Long> ids = new ArrayList<>();
        ids.add(rootId);
        Queue<Long> queue = new LinkedList<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            List<Long> childIds = adminTagRepository.findChildIds(queue.poll());
            ids.addAll(childIds);
            queue.addAll(childIds);
        }
        return ids;
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