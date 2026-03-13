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

    /**
     * 이미 로딩된 doc을 임베딩합니다. 호출자의 트랜잭션 내에서 동작합니다.
     */
    public void embedDocument(PlaceSearchDocument doc, String reviewSummary) {
        try {
            String retrievalText = retrievalTextBuilder.build(doc.getPlace(), reviewSummary);
            float[] embedding = embeddingService.embed(retrievalText);
            doc.updateEmbedding(retrievalText, embedding, embeddingService.getModelName());
            log.info("임베딩 완료 - placeId={}", doc.getPlaceId());
        } catch (Exception e) {
            doc.markFailed();
            log.warn("임베딩 실패 - placeId={}, error={}", doc.getPlaceId(), e.getMessage());
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
