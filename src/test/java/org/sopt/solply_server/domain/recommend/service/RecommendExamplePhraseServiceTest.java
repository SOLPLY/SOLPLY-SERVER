package org.sopt.solply_server.domain.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.recommend.dto.response.ExamplePhrasesGetResponse;
import org.sopt.solply_server.domain.recommend.entity.RecommendExamplePhrase;
import org.sopt.solply_server.domain.recommend.entity.RecommendTargetType;
import org.sopt.solply_server.domain.recommend.repository.RecommendExamplePhraseRepository;

@ExtendWith(MockitoExtension.class)
class RecommendExamplePhraseServiceTest {

    @Mock
    private RecommendExamplePhraseRepository recommendExamplePhraseRepository;

    @InjectMocks
    private RecommendExamplePhraseService recommendExamplePhraseService;

    @Test
    @DisplayName("조회된 문구를 레포지토리 순서대로 매핑해 반환한다")
    void mapsPhrasesInOrder() {
        given(recommendExamplePhraseRepository
                .findByTargetTypeOrderByDisplayOrderAscIdAsc(RecommendTargetType.PLACE))
                .willReturn(List.of(
                        RecommendExamplePhrase.builder().id(1L).content("서촌 조용한 독립서점").build(),
                        RecommendExamplePhrase.builder().id(2L).content("망원동 아기자기한 소품샵").build()
                ));

        ExamplePhrasesGetResponse response =
                recommendExamplePhraseService.getExamplePhrases(RecommendTargetType.PLACE);

        assertThat(response.phrases())
                .containsExactly("서촌 조용한 독립서점", "망원동 아기자기한 소품샵");
    }

    @Test
    @DisplayName("등록된 문구가 없으면 빈 리스트를 반환한다")
    void returnsEmptyListWhenNone() {
        given(recommendExamplePhraseRepository
                .findByTargetTypeOrderByDisplayOrderAscIdAsc(RecommendTargetType.COURSE))
                .willReturn(List.of());

        ExamplePhrasesGetResponse response =
                recommendExamplePhraseService.getExamplePhrases(RecommendTargetType.COURSE);

        assertThat(response.phrases()).isEmpty();
    }
}
