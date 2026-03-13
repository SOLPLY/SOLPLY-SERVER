package org.sopt.solply_server.domain.place.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.place.service.PlaceEmbeddingBatchProcessor;
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
    private final PlaceEmbeddingBatchProcessor batchProcessor;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PlaceCreatedEvent event) {
        Place place = event.place();
        log.info("장소 임베딩 생성 시작 - placeId={}", place.getId());

        PlaceSearchDocument doc = placeSearchDocumentRepository.findById(place.getId())
                .orElseGet(() -> placeSearchDocumentRepository.save(PlaceSearchDocument.init(place)));

        batchProcessor.processOne(doc);
    }
}
