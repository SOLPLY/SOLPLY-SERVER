package org.sopt.solply_server.domain.recommend.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.recommend.dto.ExamplePhraseDto;
import org.sopt.solply_server.domain.recommend.dto.response.ExamplePhrasesGetResponse;
import org.sopt.solply_server.domain.recommend.entity.RecommendTargetType;
import org.sopt.solply_server.domain.recommend.repository.RecommendExamplePhraseRepository;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendExamplePhraseService {

    private final RecommendExamplePhraseRepository recommendExamplePhraseRepository;
    private final TownValidator townValidator;

    public ExamplePhrasesGetResponse getExamplePhrases(Long townId, RecommendTargetType type) {
        townValidator.validateTownId(townId);

        List<ExamplePhraseDto> phrases = recommendExamplePhraseRepository
                .findByTown_IdAndTargetTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(townId, type)
                .stream()
                .map(ExamplePhraseDto::from)
                .toList();

        return ExamplePhrasesGetResponse.from(phrases);
    }
}
