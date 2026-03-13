package org.sopt.solply_server.domain.place.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.PlaceReviewSummary;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.place.util.RetrievalTextBuilder;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceEmbeddingBatchProcessor {

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final RetrievalTextBuilder retrievalTextBuilder;
    private final EmbeddingService embeddingService;

    @Transactional
    public void processOne(Long placeId, PlaceReviewSummary reviewSummary) {
        PlaceSearchDocument doc = placeSearchDocumentRepository.findById(placeId).orElse(null);
        if (doc == null) {
            log.warn("재임베딩 대상 문서를 찾을 수 없습니다 - placeId={}", placeId);
            return;
        }

        try {
            String retrievalText = retrievalTextBuilder.build(doc.getPlace(), reviewSummary);
            float[] embedding = embeddingService.embed(retrievalText);
            doc.updateEmbedding(retrievalText, embedding, embeddingService.getModelName());
            log.info("재임베딩 완료 - placeId={}", placeId);
        } catch (Exception e) {
            doc.markFailed();
            log.warn("재임베딩 실패 - placeId={}, error={}", placeId, e.getMessage());
        }
    }
}
