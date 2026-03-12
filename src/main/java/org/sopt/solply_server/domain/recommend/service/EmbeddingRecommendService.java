package org.sopt.solply_server.domain.recommend.service;

import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.recommend.dto.RecommendedPlaceDto;
import org.sopt.solply_server.domain.recommend.dto.response.EmbeddingPlaceRecommendGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.ai.CosineSimilarityUtil;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmbeddingRecommendService {

    private static final int TOP_K = 3;

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final EmbeddingService embeddingService;

    public EmbeddingPlaceRecommendGetResponse recommendByQuery(String query, Long townId) {
        float[] queryVector = embeddingService.embed(query);

        List<PlaceSearchDocument> candidates = placeSearchDocumentRepository.findActiveByTownIdWithEmbedding(townId);

        List<RecommendedPlaceDto> topPlaces = candidates.stream()
                .map(doc -> new ScoredDoc(doc, CosineSimilarityUtil.calculate(queryVector, doc.getEmbedding())))
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(TOP_K)
                .map(scored -> toDto(scored.doc().getPlace(), scored.score()))
                .toList();

        return new EmbeddingPlaceRecommendGetResponse(topPlaces);
    }

    private RecommendedPlaceDto toDto(Place place, double score) {
        String mainTag = place.getMainTag()
                .filter(Tag::isActive)
                .map(Tag::getName)
                .orElse(null);

        List<String> optionTags = place.getTags().stream()
                .filter(t -> t.getType() != TagType.MAIN && t.isActive())
                .map(Tag::getName)
                .toList();

        String townName = place.getTown().getName();
        String reason = buildReason(score);

        return new RecommendedPlaceDto(place.getId(), place.getName(), mainTag, optionTags, townName, reason);
    }

    private String buildReason(double score) {
        int percent = (int) Math.round(score * 100);
        if (percent >= 85) return "질문과 매우 잘 맞는 장소입니다. (" + percent + "% 유사)";
        if (percent >= 70) return "질문과 잘 맞는 장소입니다. (" + percent + "% 유사)";
        return "질문과 관련 있는 장소입니다. (" + percent + "% 유사)";
    }

    private record ScoredDoc(PlaceSearchDocument doc, double score) {}
}
