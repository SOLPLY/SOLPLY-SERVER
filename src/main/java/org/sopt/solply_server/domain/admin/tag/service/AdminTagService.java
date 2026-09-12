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
import org.sopt.solply_server.domain.place.cache.SnapshotRefresher;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.place.util.TagBitmask;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>태그 쓰기는 커밋 후 태그 맵을 통째로 다시 읽는다(수십 행).</b> 목록이 태그에서 읽는 것은
 * 이름과 활성 여부뿐이고 그 둘은 배열 밖 태그 맵에 있다. <b>어느 태그가 바뀌었는지 모으지
 * 않는다</b> — 쓰기 경로마다 훅 한 번이면 되고, 비활성 캐스케이드처럼 한 요청이 여러 태그를
 * 건드려도 셀 것이 없다.
 *
 * <p><b>태그 쓰기는 재빌드를 걸지 않는다.</b> 스냅샷에 실리는 태그 값은 대표 태그 id 하나뿐인데
 * 그것을 흔드는 유일한 수정이 타입 변경이고, 그것은 {@link #updateTag}에서 거부한다 — 대표 태그가
 * 낡을 경로가 아예 없다.
 *
 * <p>태그를 단 장소를 찾아다니지 않는 것이 이 구조의 요점이다. 대표 태그 이름은 조회 시점에
 * 합쳐지므로, 태그 하나를 고치면 그 태그를 단 장소 전부가 함께 바뀐다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTagService {

    private final AdminTagRepository adminTagRepository;
    private final AdminEntityLoader adminEntityLoader;
    private final AdminTagValidator adminTagValidator;
    private final EntityManager entityManager;
    /** 태그 맵을 <b>커밋 뒤에</b> 다시 읽게 한다 — 시점의 근거는 리프레셔 javadoc */
    private final SnapshotRefresher snapshotRefresher;
    private final SnapshotRebuildRequestRepository rebuildRequestRepository;

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

        snapshotRefresher.refreshTagViewsAfterCommit(rebuildRequestRepository.request());
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

    /**
     * <b>타입은 못 바꾼다.</b> MAIN↔OPTION이 갈리면 그 태그를 <em>대표로 쓰던</em> 장소들의
     * {@code mainTagId}가 낡는다 — 대표 태그를 뽑는 규칙이 "첫 MAIN 태그"이고 그 값은 배열 밖 표시
     * 맵이 아니라 <b>스냅샷과 함께 지어지기</b> 때문이다. 그 장소들을 찾아다니거나 스냅샷을 다시 짓는
     * 대신 수정 자체를 막는다. 타입을 실제로 바꿔야 하면 태그를 새로 만들어 옮기는 것이 맞다.
     */
    @Transactional
    public Long updateTag(Long id, AdminTagUpsertRequest req) {
        Tag tag = adminEntityLoader.getTag(id);
        if (tag.getType() != req.type()) {
            throw new BusinessException(ErrorCode.TAG_TYPE_IMMUTABLE);
        }

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

        snapshotRefresher.refreshTagViewsAfterCommit(rebuildRequestRepository.request());
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

        snapshotRefresher.refreshTagViewsAfterCommit(rebuildRequestRepository.request());
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

    /**
     * <b>여기서는 훅을 부르지 않는다.</b> 함께 내려간 자식도 맵에 반영돼야 하지만 — 목록이 대표
     * 태그 이름을 비우는 판정이 맵의 {@code active}로 이뤄지므로 빠뜨리면 그 자식이 대표인 장소는
     * 다음 전량 재빌드까지 내려간 태그의 이름을 계속 달고 나간다 — 진입 메서드의 훅 하나가
     * 맵을 통째로 다시 읽으므로 자식을 따로 셀 것이 없다.
     */
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