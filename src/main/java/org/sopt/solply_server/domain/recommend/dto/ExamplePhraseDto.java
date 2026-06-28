package org.sopt.solply_server.domain.recommend.dto;

import lombok.Builder;
import org.sopt.solply_server.domain.recommend.entity.RecommendExamplePhrase;

@Builder
public record ExamplePhraseDto(
        Long id,
        String content
) {
    public static ExamplePhraseDto from(RecommendExamplePhrase phrase) {
        return ExamplePhraseDto.builder()
                .id(phrase.getId())
                .content(phrase.getContent())
                .build();
    }
}
