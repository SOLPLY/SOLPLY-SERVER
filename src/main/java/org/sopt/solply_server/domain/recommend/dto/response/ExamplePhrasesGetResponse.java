package org.sopt.solply_server.domain.recommend.dto.response;

import java.util.List;

import lombok.Builder;

@Builder
public record ExamplePhrasesGetResponse(
        List<String> phrases
) {
    public static ExamplePhrasesGetResponse from(List<String> phrases) {
        return ExamplePhrasesGetResponse.builder()
                .phrases(phrases)
                .build();
    }
}
