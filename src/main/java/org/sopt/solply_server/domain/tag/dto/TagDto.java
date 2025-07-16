package org.sopt.solply_server.domain.tag.dto;

import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;

public record TagDto(
    Long tagId,
    String tagType,
    TagName name,
    Long parentId
){
    public static TagDto from(Tag tag){
        return new TagDto(
                tag.getId(),
                tag.getType().name(),
                tag.getName(),
                tag.getParent() != null ? tag.getParent().getId() : null
        );
    }
}

