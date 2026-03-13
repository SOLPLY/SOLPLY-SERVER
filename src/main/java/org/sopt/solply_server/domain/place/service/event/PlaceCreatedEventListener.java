package org.sopt.solply_server.domain.place.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.place.util.RetrievalTextBuilder;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceCreatedEventListener {

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final RetrievalTextBuilder retrievalTextBuilder;
    private final EmbeddingService embeddingService;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PlaceCreatedEvent event) {
        Place place = event.place();
        log.info("장소 임베딩 생성 시작 - placeId={}", place.getId());

        PlaceSearchDocument doc = placeSearchDocumentRepository
                .findById(place.getId())
                .orElseGet(() -> placeSearchDocumentRepository.save(PlaceSearchDocument.init(place)));

        if (doc.getStatus() != EmbeddingStatus.INIT) {
            log.info("이미 처리된 문서입니다. placeId={}, status={}", place.getId(), doc.getStatus());
            return;
        }

        try {
            String retrievalText = retrievalTextBuilder.build(place, null);
            float[] embedding = embeddingService.embed(retrievalText);
            doc.updateEmbedding(retrievalText, embedding, embeddingService.getModelName());
            log.info("장소 임베딩 생성 완료 - placeId={}", place.getId());
        } catch (Exception e) {
            doc.markFailed();
            log.warn("장소 임베딩 생성 실패 - placeId={}, error={}", place.getId(), e.getMessage());
        }
    }
}
