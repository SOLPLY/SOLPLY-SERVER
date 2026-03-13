package org.sopt.solply_server.domain.place.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.sopt.solply_server.domain.place.entity.PlaceReviewSummary;
import org.sopt.solply_server.domain.place.entity.PlaceSearchDocument;
import org.sopt.solply_server.domain.place.repository.PlaceReviewSummaryRepository;
import org.sopt.solply_server.domain.place.repository.PlaceSearchDocumentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceSearchDocumentService {

    private final PlaceSearchDocumentRepository placeSearchDocumentRepository;
    private final PlaceReviewSummaryRepository placeReviewSummaryRepository;
    private final PlaceEmbeddingBatchProcessor batchProcessor;

    @Transactional
    public void markDirtyByPlaceId(Long placeId) {
        placeSearchDocumentRepository.findById(placeId)
                .ifPresent(doc -> {
                    doc.markDirty();
                    log.info("PlaceSearchDocument DIRTY 전환 - placeId={}", placeId);
                });
    }

    @Transactional
    public void markDirtyByTagId(Long tagId) {
        List<PlaceSearchDocument> docs = placeSearchDocumentRepository.findAllByTagId(tagId);
        docs.forEach(doc -> {
            doc.markDirty();
            log.info("PlaceSearchDocument DIRTY 전환 (태그 변경) - placeId={}, tagId={}", doc.getPlaceId(), tagId);
        });
    }

    @Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
    public void processDirtyDocuments() {
        List<PlaceSearchDocument> docs = placeSearchDocumentRepository
                .findAllByStatusIn(List.of(EmbeddingStatus.DIRTY, EmbeddingStatus.FAILED));

        log.info("DIRTY/FAILED 문서 재임베딩 배치 시작 - 대상 수: {}", docs.size());

        List<Long> placeIds = docs.stream().map(PlaceSearchDocument::getPlaceId).toList();
        Map<Long, PlaceReviewSummary> summaryMap = placeReviewSummaryRepository.findAllById(placeIds)
                .stream()
                .collect(Collectors.toMap(PlaceReviewSummary::getPlaceId, s -> s));

        for (PlaceSearchDocument doc : docs) {
            batchProcessor.processOne(doc.getPlaceId(), summaryMap.get(doc.getPlaceId()));
        }

        log.info("DIRTY/FAILED 문서 재임베딩 배치 완료");
    }
}
