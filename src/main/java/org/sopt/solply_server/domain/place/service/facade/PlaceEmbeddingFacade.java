package org.sopt.solply_server.domain.place.service.facade;

import java.util.ArrayList;
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

    private static final int CHUNK_SIZE = 50;
    private static final List<EmbeddingStatus> INIT_TARGET_STATUSES =
            List.of(EmbeddingStatus.INIT, EmbeddingStatus.FAILED);
    private static final List<EmbeddingStatus> REEMBEDDING_TARGET_STATUSES =
            List.of(EmbeddingStatus.INIT, EmbeddingStatus.DIRTY, EmbeddingStatus.FAILED);

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final PlaceReviewSummaryRepository placeReviewSummaryRepository;
    private final PlaceEmbeddingBatchProcessor batchProcessor;

    public void initializePendingDocuments() {
        List<Long> pendingIds = placeSearchDocumentRepository.findAllByStatusIn(INIT_TARGET_STATUSES)
                .stream().map(PlaceSearchDocument::getPlaceId).toList();

        log.info("신규 임베딩 초기화 시작 - 대상 수: {}", pendingIds.size());
        processInChunks(pendingIds, Map.of());
        log.info("신규 임베딩 초기화 완료");
    }

    @Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
    public void reembedStaleDocuments() {
        List<Long> staleIds = placeSearchDocumentRepository.findAllByStatusIn(REEMBEDDING_TARGET_STATUSES)
                .stream().map(PlaceSearchDocument::getPlaceId).toList();

        log.info("재임베딩 배치 시작 - 대상 수: {}", staleIds.size());

        Map<Long, String> reviewSummaryByPlaceId = placeReviewSummaryRepository.findAllById(staleIds)
                .stream()
                .collect(Collectors.toMap(
                        summary -> summary.getPlaceId(),
                        summary -> summary.getSummaryContent()
                ));

        processInChunks(staleIds, reviewSummaryByPlaceId);
        log.info("재임베딩 배치 완료");
    }

    private void processInChunks(List<Long> placeIds, Map<Long, String> reviewSummaryByPlaceId) {
        List<List<Long>> chunks = partition(placeIds, CHUNK_SIZE);
        for (List<Long> chunk : chunks) {
            try {
                batchProcessor.processBatch(chunk, reviewSummaryByPlaceId);
            } catch (Exception e) {
                log.warn("청크 임베딩 실패 - size={}, error={}", chunk.size(), e.getMessage());
                batchProcessor.markChunkFailed(chunk);
            }
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
