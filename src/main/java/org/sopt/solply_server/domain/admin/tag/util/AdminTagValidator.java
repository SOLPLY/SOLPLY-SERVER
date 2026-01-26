package org.sopt.solply_server.domain.admin.tag.util;

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

    public void validateParentForAdmin(
            TagType type,
            Long parentId,
            boolean requestedActive,
            Tag parent // ← service에서 로드한 parent
    ) {
        if (type == TagType.MAIN) {
            if (parentId != null) {
                throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
            }
            return;
        }

        // OPTION1/2
        if (parentId == null) {
            throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
        }

        // parent 타입 검증 (active는 보지 않음)
        if (!tagRepository.existsByIdAndType(parentId, TagType.MAIN)) {
            throw new BusinessException(ErrorCode.INVALID_TAG_RELATIONSHIP);
        }

        // parent 비활성인데 자식을 활성화하려는 경우만 금지
        if (requestedActive && !parent.isActive()) {
            throw new BusinessException(ErrorCode.CANNOT_ACTIVATE_TAG_PARENT_INACTIVE);
        }
    }
}