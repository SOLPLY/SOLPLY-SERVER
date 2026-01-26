package org.sopt.solply_server.domain.admin.town.util;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.tag.repository.TagRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminTagValidator {

    private final TagRepository tagRepository;
    private final EntityLoader entityLoader;

    public Tag resolveAndValidateParentForAdmin(TagType type, Long parentId, boolean requestedActive) {
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

}