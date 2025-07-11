package org.sopt.solply_server.domain.tag.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record TagResponse(
        List<TagDto> tags
) {

    public static TagResponse from(List<TagDto> tags) {
        return new TagResponse(tags);
    }
}
