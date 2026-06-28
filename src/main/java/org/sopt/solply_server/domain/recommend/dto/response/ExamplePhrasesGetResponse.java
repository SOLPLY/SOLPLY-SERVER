package org.sopt.solply_server.domain.recommend.dto.response;

import java.util.List;

import lombok.Builder;
import org.sopt.solply_server.domain.recommend.dto.ExamplePhraseDto;

@Builder
public record ExamplePhrasesGetResponse(
        List<ExamplePhraseDto> phrases
) {
    public static ExamplePhrasesGetResponse from(List<ExamplePhraseDto> phrases) {
        return ExamplePhrasesGetResponse.builder()
                .phrases(phrases)
                .build();
    }
}
