package org.sopt.solply_server.domain.tag.dto;

import org.sopt.solply_server.domain.tag.entity.Tag;

public record TagDto(
    Long tagId,
    String tagType,
    String name,
    Long parentId
){
    public static TagDto of(Tag tag){
        return new TagDto(
                tag.getId(),
                tag.getType().name(),
                tag.getName().getDisplayName(),
                tag.getParent() != null ? tag.getParent().getId() : null
        );
    }
}

