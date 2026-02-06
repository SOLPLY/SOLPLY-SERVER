package org.sopt.solply_server.domain.tag.util;

import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.entity.TagUsage;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TagValidator {

    private final TagRepository tagRepository;

    /** PLACE(장소) 생성/수정용: active + usage=PLACE + 계층/타입/관계 검증 */
    public void validatePlaceTagConditions(Long mainTagId, List<Long> subTagAIdList, List<Long> subTagBIdList) {
        validateHierarchicalTags(mainTagId, subTagAIdList, subTagBIdList, TagUsage.PLACE);
    }

    /** COURSE(코스) 생성/수정용: active + usage=COURSE */
    public void validateCourseTagCondition(Long courseTagId) {
        if (courseTagId == null) throw new BusinessException(ErrorCode.NOT_FOUND_TAG);

        if (!tagRepository.existsByIdAndTagUsage(courseTagId, TagUsage.COURSE)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
        }
    }

    public void validateCourseTagEntity(Tag tag) {
        if (tag == null) return;
        if (!tag.isActive()) throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
        if (tag.getTagUsage() != TagUsage.COURSE) throw new BusinessException(ErrorCode.INVALID_TAG_USAGE);
        if (tag.getType() != TagType.MAIN) throw new BusinessException(ErrorCode.INVALID_TAG_TYPE);
    }

    public void validateTagIsActive(Tag tag) {
        if (!tag.isActive()) throw new BusinessException(ErrorCode.NOT_ACTIVE_TAG);
    }

    // =======================
    // 내부: PLACE 계층 검증
    // =======================
    private void validateHierarchicalTags(Long mainTagId, List<Long> subA, List<Long> subB, TagUsage usage) {
        if (mainTagId == null) throw new BusinessException(ErrorCode.NOT_FOUND_TAG);

        Tag mainTag = tagRepository.findByIdWithParentAndActiveTrue(mainTagId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_TAG));

        if (mainTag.getTagUsage() != usage) throw new BusinessException(ErrorCode.INVALID_TAG_USAGE);
        if (mainTag.getType() != TagType.MAIN) throw new BusinessException(ErrorCode.INVALID_TAG_TYPE);

        List<Long> option1Ids = normalizeIds(subA);
        List<Long> option2Ids = normalizeIds(subB);

        if (option1Ids.isEmpty() && option2Ids.isEmpty()) return;

        Set<Long> allSubIds = new LinkedHashSet<>();
        allSubIds.addAll(option1Ids);
        allSubIds.addAll(option2Ids);

        List<Tag> loadedSubs = tagRepository.findAllByIdInWithParentAndActiveTrue(allSubIds);

        // active=true 기준으로 못 불러온 게 있다면(비활성 포함) -> NOT_FOUND
        if (loadedSubs.size() != allSubIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
        }

        // usage 체크
        for (Tag t : loadedSubs) {
            if (t.getTagUsage() != usage) throw new BusinessException(ErrorCode.INVALID_TAG_USAGE);
        }

        Map<Long, Tag> subMap = loadedSubs.stream()
                .collect(Collectors.toMap(Tag::getId, x -> x));

        validateSubGroup(mainTag, subMap, option1Ids, TagType.OPTION1);
        validateSubGroup(mainTag, subMap, option2Ids, TagType.OPTION2);
    }

    private void validateSubGroup(Tag mainTag, Map<Long, Tag> subMap, List<Long> requestedIds, TagType expectedType) {
        for (Long id : requestedIds) {
            Tag t = subMap.get(id);
            if (t == null) throw new BusinessException(ErrorCode.NOT_FOUND_TAG);

            if (t.getType() != expectedType) throw new BusinessException(ErrorCode.INVALID_TAG_TYPE);

            if (t.getParent() == null || !t.getParent().getId().equals(mainTag.getId())) {
                throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
            }
        }
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}