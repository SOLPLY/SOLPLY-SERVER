package org.sopt.solply_server.domain.place.service.facade;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.sopt.solply_server.domain.place.service.PlaceEmbeddingBatchProcessor;
import org.sopt.solply_server.domain.review.repository.PlaceReviewSummaryRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceEmbeddingFacade {

    private static final List<EmbeddingStatus> INIT_TARGET_STATUSES =
            List.of(EmbeddingStatus.INIT);
    private static final List<EmbeddingStatus> REEMBEDDING_TARGET_STATUSES =
            List.of(EmbeddingStatus.DIRTY, EmbeddingStatus.FAILED);

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final PlaceReviewSummaryRepository placeReviewSummaryRepository;
    private final PlaceEmbeddingBatchProcessor batchProcessor;

    public void initializePendingDocuments() {
        List<PlaceSearchDocument> pendingDocuments = placeSearchDocumentRepository
                .findAllByStatusIn(INIT_TARGET_STATUSES);

        log.info("신규 임베딩 초기화 시작 - 대상 수: {}", pendingDocuments.size());

        for (PlaceSearchDocument document : pendingDocuments) {
            batchProcessor.processOne(document.getPlaceId(), null);
        }

        log.info("신규 임베딩 초기화 완료");
    }

    @Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
    public void reembedStaleDocuments() {
        List<PlaceSearchDocument> staleDocuments = placeSearchDocumentRepository
                .findAllByStatusIn(REEMBEDDING_TARGET_STATUSES);

        log.info("재임베딩 배치 시작 - 대상 수: {}", staleDocuments.size());

        List<Long> placeIds = staleDocuments.stream().map(PlaceSearchDocument::getPlaceId).toList();
        Map<Long, String> reviewSummaryByPlaceId = placeReviewSummaryRepository.findAllById(placeIds)
                .stream()
                .collect(Collectors.toMap(
                        summary -> summary.getPlaceId(),
                        summary -> summary.getSummaryContent()
                ));

        for (PlaceSearchDocument document : staleDocuments) {
            batchProcessor.processOne(document.getPlaceId(), reviewSummaryByPlaceId.get(document.getPlaceId()));
        }

        log.info("재임베딩 배치 완료");
    }
}
