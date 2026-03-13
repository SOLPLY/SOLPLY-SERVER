package org.sopt.solply_server.domain.place.service;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    public void processOne(Long placeId, String reviewSummary) {
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

    @Transactional
    public void processBatch(List<Long> placeIds, Map<Long, String> reviewSummaryByPlaceId) {
        List<PlaceSearchDocument> docs = placeSearchDocumentRepository.findAllByPlaceIdInWithPlace(placeIds);

        List<String> retrievalTexts = docs.stream()
                .map(doc -> retrievalTextBuilder.build(doc.getPlace(), reviewSummaryByPlaceId.get(doc.getPlaceId())))
                .toList();

        List<float[]> embeddings = embeddingService.embedAll(retrievalTexts);

        for (int i = 0; i < docs.size(); i++) {
            docs.get(i).updateEmbedding(retrievalTexts.get(i), embeddings.get(i), embeddingService.getModelName());
        }

        log.info("배치 임베딩 완료 - count={}", docs.size());
    }

    @Transactional
    public void markChunkFailed(List<Long> placeIds) {
        List<PlaceSearchDocument> docs = placeSearchDocumentRepository.findAllById(placeIds);
        docs.forEach(PlaceSearchDocument::markFailed);
        log.warn("청크 임베딩 실패로 FAILED 처리 - count={}", docs.size());
    }
}
