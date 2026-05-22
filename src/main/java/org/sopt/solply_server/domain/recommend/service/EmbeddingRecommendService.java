package org.sopt.solply_server.domain.recommend.service;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.recommend.dto.RecommendedPlaceDto;
import org.sopt.solply_server.domain.recommend.dto.response.EmbeddingPlaceRecommendGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.ai.CosineSimilarityUtil;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.sopt.solply_server.global.ai.ReasonGenerationService;
import org.sopt.solply_server.global.ai.ReasonGenerationService.PlaceContext;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmbeddingRecommendService {

    private static final int TOP_K = 3;

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final EmbeddingService embeddingService;
    private final ReasonGenerationService reasonGenerationService;
    private final EntityLoader entityLoader;
    private final ImageUrlProvider imageUrlProvider;

    public EmbeddingPlaceRecommendGetResponse recommendByQuery(String query, Long townId, Long userId) {
        User user = entityLoader.getUser(userId);
        String userName = user.getNickname();

        float[] queryVector = embeddingService.embed(query);

        // 서울 전체에 대한 처리를 위한 로직
        Town town = entityLoader.getActiveTown(townId);
        List<PlaceSearchDocument> candidates = town.getParent() == null
                ? placeSearchDocumentRepository.findActiveByParentTownIdWithEmbedding(townId)
                : placeSearchDocumentRepository.findActiveByTownIdWithEmbedding(townId);

        List<ScoredDoc> topDocs = candidates.stream()
                .filter(doc -> {
                    if (doc.getEmbedding() == null) {
                        log.warn("임베딩 데이터 손상으로 추천 후보에서 제외합니다. placeId={}", doc.getPlace().getId());
                        return false;
                    }
                    return true;
                })
                .flatMap(doc -> {
                    OptionalDouble score = CosineSimilarityUtil.calculate(queryVector, doc.getEmbedding());
                    if (score.isEmpty()) {
                        log.warn("임베딩 차원 불일치로 추천 후보에서 제외합니다. placeId={}, queryDim={}, docDim={}",
                                doc.getPlace().getId(), queryVector.length, doc.getEmbedding().length);
                        return java.util.stream.Stream.empty();
                    }
                    return java.util.stream.Stream.of(new ScoredDoc(doc, score.getAsDouble()));
                })
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(TOP_K)
                .toList();

        if (topDocs.isEmpty()) {
            return new EmbeddingPlaceRecommendGetResponse(List.of());
        }

        List<PlaceContext> placeContexts = topDocs.stream()
                .map(sd -> toPlaceContext(sd.doc()))
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

    private PlaceContext toPlaceContext(PlaceSearchDocument doc) {
        Place place = doc.getPlace();
        return new PlaceContext(place.getName(), place.getTown().getName(), doc.getRetrievalText());
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
                imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                mainTag,
                optionTags,
                place.getTown().getId(),
                place.getTown().getName(),
                reason
        );
    }

    private record ScoredDoc(PlaceSearchDocument doc, double score) {}
}
