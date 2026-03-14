package org.sopt.solply_server.domain.place.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.dto.PlaceRetrievalData;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.place.util.RetrievalTextBuilder;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceEmbeddingBatchProcessor {

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final PlaceRepository placeRepository;
    private final RetrievalTextBuilder retrievalTextBuilder;
    private final EmbeddingService embeddingService;
    private final EntityManager entityManager;

    /**
     * 단건 임베딩. 호출자의 트랜잭션 내에서 동작합니다.
     */
    public void processOne(PlaceSearchDocument doc) {
        Place place = placeRepository.findAllByIdWithTownAndTags(List.of(doc.getPlaceId()))
                .stream().findFirst().orElseThrow();

        try {
            PlaceRetrievalData data = buildRetrievalData(place, null);
            String retrievalText = retrievalTextBuilder.build(data);
            // API 호출 직전 시점을 기록하여, 완료 후 그 사이 수정 여부를 판단합니다.
            LocalDateTime startedAt = LocalDateTime.now();
            float[] embedding = embeddingService.embed(retrievalText);
            doc.updateEmbedding(retrievalText, embedding, embeddingService.getModelName(), startedAt);
            log.info("임베딩 완료 - placeId={}", doc.getPlaceId());
        } catch (Exception e) {
            doc.markFailed();
            log.warn("임베딩 실패 - placeId={}, error={}", doc.getPlaceId(), e.getMessage());
        }
    }

    @Transactional
    public void processBatch(List<Long> placeIds, Map<Long, String> reviewSummaryByPlaceId) {
        Map<Long, Place> placeById = placeRepository.findAllByIdWithTownAndTags(placeIds)
                .stream().collect(Collectors.toMap(Place::getId, p -> p));

        List<PlaceSearchDocument> docs = placeSearchDocumentRepository.findAllById(placeIds);

        List<PlaceSearchDocument> embeddableDocs = new ArrayList<>();
        List<String> retrievalTexts = new ArrayList<>();

        for (PlaceSearchDocument doc : docs) {
            Place place = placeById.get(doc.getPlaceId());
            PlaceRetrievalData data = buildRetrievalData(place, reviewSummaryByPlaceId.get(doc.getPlaceId()));
            embeddableDocs.add(doc);
            retrievalTexts.add(retrievalTextBuilder.build(data));
        }

        if (embeddableDocs.isEmpty()) return;

        // API 호출 직전 시점을 기록하여, 완료 후 그 사이 수정된 문서를 감지합니다.
        LocalDateTime startedAt = LocalDateTime.now();
        List<float[]> embeddings = embeddingService.embedAll(retrievalTexts);

        for (int i = 0; i < embeddableDocs.size(); i++) {
            PlaceSearchDocument doc = embeddableDocs.get(i);
            // 외부 트랜잭션의 변경(예: markDirtyByTagId)을 감지하기 위해 DB에서 최신 상태를 재조회합니다.
            entityManager.refresh(doc);
            doc.updateEmbedding(retrievalTexts.get(i), embeddings.get(i), embeddingService.getModelName(), startedAt);
        }

        log.info("배치 임베딩 완료 - count={}", embeddableDocs.size());
    }

    @Transactional
    public void markChunkFailed(List<Long> placeIds) {
        List<PlaceSearchDocument> docs = placeSearchDocumentRepository.findAllById(placeIds);
        docs.forEach(PlaceSearchDocument::markFailed);
        log.warn("청크 임베딩 실패로 FAILED 처리 - count={}", docs.size());
    }

    private PlaceRetrievalData buildRetrievalData(Place place, String reviewSummary) {
        List<Tag> tags = place.getTags(); // findAllByIdWithTownAndTags로 이미 로딩됨

        String mainTagName = tags.stream()
                .filter(Tag::isActive)
                .filter(t -> t.getType() == TagType.MAIN)
                .map(Tag::getName)
                .findFirst().orElse("장소");

        List<String> tagMeanings = tags.stream()
                .filter(Tag::isActive)
                .filter(t -> t.getMeaning() != null && !t.getMeaning().isBlank())
                .map(Tag::getMeaning)
                .toList();

        return new PlaceRetrievalData(
                place.getName(),
                place.getTown().getName(),    // JOIN FETCH로 이미 로딩됨
                mainTagName,
                place.getIntroduction(),
                place.getCheckpoints(),       // @BatchSize(50)으로 배치 로딩
                tagMeanings,
                reviewSummary
        );
    }
}
