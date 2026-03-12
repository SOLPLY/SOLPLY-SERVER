package org.sopt.solply_server.domain.recommend.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceReviewSummary;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceReviewSummaryRepository;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.recommend.dto.RecommendedPlaceDto;
import org.sopt.solply_server.domain.recommend.dto.response.EmbeddingPlaceRecommendGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.ai.CosineSimilarityUtil;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.sopt.solply_server.global.ai.ReasonGenerationService;
import org.sopt.solply_server.global.ai.ReasonGenerationService.PlaceContext;
import org.sopt.solply_server.global.util.EntityLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmbeddingRecommendService {

    private static final int TOP_K = 3;

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final PlaceReviewSummaryRepository placeReviewSummaryRepository;
    private final EmbeddingService embeddingService;
    private final ReasonGenerationService reasonGenerationService;
    private final EntityLoader entityLoader;

    public EmbeddingPlaceRecommendGetResponse recommendByQuery(String query, Long townId, Long userId) {
        User user = entityLoader.getUser(userId);
        String userName = user.getNickname();

        float[] queryVector = embeddingService.embed(query);

        List<PlaceSearchDocument> candidates =
                placeSearchDocumentRepository.findActiveByTownIdWithEmbedding(townId);

        List<ScoredDoc> topDocs = candidates.stream()
                .map(doc -> new ScoredDoc(doc, CosineSimilarityUtil.calculate(queryVector, doc.getEmbedding())))
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(TOP_K)
                .toList();

        if (topDocs.isEmpty()) {
            return new EmbeddingPlaceRecommendGetResponse(List.of());
        }

        List<Long> topPlaceIds = topDocs.stream()
                .map(sd -> sd.doc().getPlace().getId())
                .toList();

        Map<Long, String> reviewSummaryByPlaceId = placeReviewSummaryRepository.findAllById(topPlaceIds).stream()
                .collect(Collectors.toMap(PlaceReviewSummary::getPlaceId, PlaceReviewSummary::getSummaryContent));

        List<PlaceContext> placeContexts = topDocs.stream()
                .map(sd -> toPlaceContext(sd.doc().getPlace(), reviewSummaryByPlaceId))
                .toList();

        List<String> reasons = reasonGenerationService.generateReasons(query, userName, placeContexts);

        List<RecommendedPlaceDto> result = new java.util.ArrayList<>();
        for (int i = 0; i < topDocs.size(); i++) {
            Place place = topDocs.get(i).doc().getPlace();
            String reason = (i < reasons.size()) ? reasons.get(i) : "";
            result.add(toDto(place, reason));
        }

        return new EmbeddingPlaceRecommendGetResponse(result);
    }

    private PlaceContext toPlaceContext(Place place, Map<Long, String> reviewSummaryByPlaceId) {
        List<String> tagNames = place.getTags().stream()
                .filter(Tag::isActive)
                .map(Tag::getName)
                .toList();
        String reviewSummary = reviewSummaryByPlaceId.get(place.getId());
        return new PlaceContext(place.getName(), tagNames, place.getIntroduction(), reviewSummary);
    }

    private RecommendedPlaceDto toDto(Place place, String reason) {
        String mainTag = place.getMainTag()
                .filter(Tag::isActive)
                .map(Tag::getName)
                .orElse(null);

        List<String> optionTags = place.getTags().stream()
                .filter(t -> t.getType() != TagType.MAIN && t.isActive())
                .map(Tag::getName)
                .toList();

        return new RecommendedPlaceDto(
                place.getId(),
                place.getName(),
                mainTag,
                optionTags,
                place.getTown().getName(),
                reason
        );
    }

    private record ScoredDoc(PlaceSearchDocument doc, double score) {}
}
