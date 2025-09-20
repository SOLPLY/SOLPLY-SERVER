package org.sopt.solply_server.domain.tag.util;

import java.util.List;
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

    public void validateTagConditions(Long mainTagId, List<Long> subTagAIdList, List<Long> subTagBIdList) {
        // 메인 태그 검증
        validateTagType(mainTagId, TagType.MAIN);

        // 서브 태그 검증
        validateSubTags(mainTagId, subTagAIdList, TagType.OPTION1);
        validateSubTags(mainTagId, subTagBIdList, TagType.OPTION2);
    }

    // 서브 태그 검증 메서드
    public void validateSubTags(final Long mainTagId, final List<Long> subTagIdList, final TagType tagType) {
        if (subTagIdList == null || subTagIdList.isEmpty()) {
            return; // 서브 태그가 없는 경우는 검증하지 않음
        }
        for (Long subTagId : subTagIdList) {
            validateTagType(subTagId, tagType);
        }
        validateTagListRelation(mainTagId, subTagIdList);
    }

    public void validateTagType(Long tagId, TagType tagType) {
        if (!tagRepository.existsById(tagId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_TAG);
        } else if (!tagRepository.existsByIdAndType(tagId, tagType)) {
            throw new BusinessException(ErrorCode.INVALID_TAG_TYPE);
        }
    }

    public void validateTagListRelation(Long mainTagId, List<Long> subTagIds) {
        Tag mainTag = tagRepository.findById(mainTagId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_TAG));
        for (Long subTagId : subTagIds) {
            Tag subtag = tagRepository.findById(subTagId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_TAG));
            if (!subtag.getParent().equals(mainTag)) {
                throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
            }
        }
    }



}