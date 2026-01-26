package org.sopt.solply_server.domain.tag.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TagValidator {

    private final TagRepository tagRepository;

    public void validateTagType(Long tagId, TagType tagType) {
        if (tagId == null) throw new BusinessException(ErrorCode.NOT_FOUND_TAG);

        boolean ok = tagRepository.existsByIdAndTypeAndActiveTrue(tagId, tagType);
        if (!ok) throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
    }

    public void validateTagConditions(Long mainTagId, List<Long> subTagAIdList, List<Long> subTagBIdList) {
        if (mainTagId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
        }

        // 1) mainTag: active + MAIN
        Tag mainTag = tagRepository.findByIdWithParentAndActiveTrue(mainTagId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_TAG));
        if (mainTag.getType() != TagType.MAIN) {
            throw new BusinessException(ErrorCode.INVALID_TAG_TYPE);
        }

        // 2) 서브 태그들 정규화(Null 제거 + 중복 제거)
        List<Long> option1Ids = normalizeIds(subTagAIdList);
        List<Long> option2Ids = normalizeIds(subTagBIdList);

        // 서브 없으면 main만 검증하고 끝
        if (option1Ids.isEmpty() && option2Ids.isEmpty()) {
            return;
        }

        // 3) 서브 태그 전체를 한 번에 로드 (active=true, parent fetch)
        Set<Long> allSubIds = new LinkedHashSet<>();
        allSubIds.addAll(option1Ids);
        allSubIds.addAll(option2Ids);

        List<Tag> loadedSubs = tagRepository.findAllByIdInWithParentAndActiveTrue(allSubIds);

        // active=true 기준으로 못 불러온 게 있다면 -> NOT_FOUND_TAG (비활성 포함)
        if (loadedSubs.size() != allSubIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
        }

        // 4) 타입 검증 + parent 관계 검증
        Map<Long, Tag> subMap = loadedSubs.stream().collect(Collectors.toMap(Tag::getId, x -> x));
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
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}