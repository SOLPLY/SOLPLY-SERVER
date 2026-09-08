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
import org.sopt.solply_server.domain.place.cache.PlaceListSnapshotRefresher;
import org.sopt.solply_server.domain.place.util.TagBitmask;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.AdminEntityLoader;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>태그 쓰기는 거의 목록 사진에 닿지 않는다.</b> 목록이 태그에서 읽는 것은 이름과 활성
 * 여부뿐이고 그 둘은 배열 밖 태그 맵에 있으므로, 여기서는 <em>언제나</em> 맵을 고친다 — 규칙이
 * 하나라 생성도 예외를 두지 않는다.
 *
 * <p><b>예외는 하나, 태그 타입 변경이다.</b> {@link #updateTag}만 사진을 다시 찍는다 — 이유는
 * 그쪽 javadoc.
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
    /** 태그 표시값을 <b>커밋 뒤에</b> 맵에 넣는다 — 시점의 근거는 리프레셔 javadoc */
    private final PlaceListSnapshotRefresher placeListSnapshotRefresher;

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

        placeListSnapshotRefresher.patchTagViewAfterCommit(tagId);
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
     * <b>여기 하나가 태그 쓰기 중 유일하게 전량 재빌드를 걸 수 있다.</b> 이 API는 {@code type}도
     * 바꿀 수 있는데(검증기가 막지 않는다), MAIN↔OPTION이 바뀌면 그 태그를 <em>대표로 쓰던</em>
     * 장소들의 {@code mainTagId}가 낡는다 — 대표 태그를 뽑는 규칙이 "첫 MAIN 태그"이고 그 값은
     * 배열 밖 표시 맵이 아니라 <b>사진과 함께 지어지기</b> 때문이다. 그 장소들을 찾아다니는 대신
     * 사진을 다시 찍는다. 같은 트랜잭션에 전량과 패치가 함께 걸리면 전량만 도는 규칙이 이미 있어
     * 아래 패치 훅은 그대로 둔다({@code PlaceListSnapshotRefresher}).
     */
    @Transactional
    public Long updateTag(Long id, AdminTagUpsertRequest req) {
        Tag tag = adminEntityLoader.getTag(id);
        TagType previousType = tag.getType();

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

        if (previousType != tag.getType()) {
            placeListSnapshotRefresher.refreshAfterCommit();
        }
        placeListSnapshotRefresher.patchTagViewAfterCommit(id);
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

        placeListSnapshotRefresher.patchTagViewAfterCommit(id);
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
     * <b>내려간 자식도 하나하나 맵에 넣는다.</b> 목록이 대표 태그 이름을 비우는 판정은 맵의
     * {@code active}로 이뤄지므로, 부모만 넣고 자식을 빠뜨리면 그 자식이 대표인 장소는 다음 전량
     * 재빌드(≤10분)까지 <b>내려간 태그의 이름을 계속 달고</b> 나가면서 아무 오류도 내지 않는다.
     *
     * <p>훅을 여러 번 부르는 값은 없다 — 리프레셔가 트랜잭션당 모아 커밋 뒤 한 번에 넣는다.
     */
    private void deactivateCascade(Long parentId) {
        List<Tag> children = adminTagRepository.findChildren(parentId);
        for (Tag child : children) {
            if (child.isActive()) {
                child.setActive(false);
                placeListSnapshotRefresher.patchTagViewAfterCommit(child.getId());
                deactivateCascade(child.getId());
            }
        }
    }
}