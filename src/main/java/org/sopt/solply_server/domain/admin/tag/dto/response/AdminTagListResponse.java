package org.sopt.solply_server.domain.admin.tag.dto.response;

import java.util.List;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;

public record AdminTagListResponse(
        List<AdminTagSummaryDto> tags
) {
    public static AdminTagListResponse of(List<AdminTagSummaryDto> tags) {
        return new AdminTagListResponse(tags);
    }

    public record AdminTagSummaryDto(
            Long id,
            TagType type,
            String name,
            String parentName,
            boolean active,
            String tagUsage
    ) {
        public static AdminTagSummaryDto from(Tag t) {
            return new AdminTagSummaryDto(
                    t.getId(),
                    t.getType(),
                    t.getName(),
                    t.getParent() == null ? null : t.getParent().getName(),
                    t.isActive(),
                    t.getTagUsage().name()
            );
        }
    }
}