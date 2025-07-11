package org.sopt.solply_server.domain.tag.dto.response;

import lombok.Builder;
import org.sopt.solply_server.domain.tag.dto.TagDto;

import java.util.List;

@Builder
public record TagListGetResponse(
        List<TagDto> tags
) {

    public static TagListGetResponse from(List<TagDto> tags) {
        return new TagListGetResponse(tags);
    }
}
