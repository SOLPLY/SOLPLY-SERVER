package org.sopt.solply_server.domain.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class RecommendExamplePhraseServiceTest {

    @Mock
    private RecommendExamplePhraseRepository recommendExamplePhraseRepository;

    @Mock
    private TownValidator townValidator;

    @InjectMocks
    private RecommendExamplePhraseService recommendExamplePhraseService;

    @Test
    @DisplayName("townId가 존재하지 않으면 NOT_FOUND_TOWN 예외를 던지고 조회를 시도하지 않는다")
    void throwsNotFoundTownAndSkipsQuery() {
        Long townId = 999L;
        doThrow(new BusinessException(ErrorCode.NOT_FOUND_TOWN))
                .when(townValidator).validateTownId(townId);

        assertThatThrownBy(() ->
                recommendExamplePhraseService.getExamplePhrases(townId, RecommendTargetType.PLACE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND_TOWN);

        verify(recommendExamplePhraseRepository, never())
                .findByTown_IdAndTargetTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(any(), any());
    }

    @Test
    @DisplayName("조회된 문구를 레포지토리 순서대로 매핑해 반환한다")
    void mapsPhrasesInOrder() {
        Long townId = 1L;
        given(recommendExamplePhraseRepository
                .findByTown_IdAndTargetTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(townId, RecommendTargetType.PLACE))
                .willReturn(List.of(
                        RecommendExamplePhrase.builder().id(1L).content("혼자 조용히 책 읽기 좋은 곳").build(),
                        RecommendExamplePhrase.builder().id(2L).content("퇴근 후 가볍게 들르기 좋은 곳").build()
                ));

        ExamplePhrasesGetResponse response =
                recommendExamplePhraseService.getExamplePhrases(townId, RecommendTargetType.PLACE);

        assertThat(response.phrases()).hasSize(2);
        assertThat(response.phrases()).extracting("id").containsExactly(1L, 2L);
        assertThat(response.phrases()).extracting("content")
                .containsExactly("혼자 조용히 책 읽기 좋은 곳", "퇴근 후 가볍게 들르기 좋은 곳");
    }

    @Test
    @DisplayName("등록된 문구가 없으면 빈 리스트를 반환한다")
    void returnsEmptyListWhenNone() {
        Long townId = 1L;
        given(recommendExamplePhraseRepository
                .findByTown_IdAndTargetTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(townId, RecommendTargetType.COURSE))
                .willReturn(List.of());

        ExamplePhrasesGetResponse response =
                recommendExamplePhraseService.getExamplePhrases(townId, RecommendTargetType.COURSE);

        assertThat(response.phrases()).isEmpty();
    }
}
