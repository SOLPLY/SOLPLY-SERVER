package org.sopt.solply_server.domain.tag.util;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TagValidator {

    public void validateMainTag(Tag mainTag)  {
        if (mainTag.getType() != TagType.MAIN) {
            throw new BusinessException(ErrorCode.INVALID_MAIN_TAG);
        }
    }

    public void validateSubTag(Tag subTag)  {
        if (subTag.getType() != TagType.MAIN) {
            throw new BusinessException(ErrorCode.INVALID_SUB_TAG);
        }
    }

    public void validateMainTagAndSubTag(Tag mainTag, Tag subTag) {
        validateMainTag(mainTag);
        validateSubTag(subTag);
        validateTagRelation(mainTag, subTag);
    }

    public void validateTagRelation(Tag mainTag, Tag subTag) {
        if (subTag.getType() != TagType.OPTION1 && subTag.getType() != TagType.OPTION2) {
            throw new BusinessException(ErrorCode.INVALID_SUB_TAG);
        }

        // mainTag, subTag 관계 검증
        if (subTag.getParent() == null || !subTag.getParent().equals(mainTag)) {
            throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
        }
    }
}