package org.sopt.solply_server.domain.course.service.facade;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.repository.CourseSearchDocumentRepository;
import org.sopt.solply_server.domain.course.service.CourseEmbeddingBatchProcessor;
import org.sopt.solply_server.domain.place.entity.EmbeddingStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseEmbeddingFacade {

    /** 한 번에 OpenAI API에 전송할 코스 수. Rate limit 대응 및 부분 실패 격리를 위해 분할한다. */
    private static final int EMBEDDING_CHUNK_SIZE = 50;

    private static final List<EmbeddingStatus> REEMBEDDING_TARGET_STATUSES =
            List.of(EmbeddingStatus.INIT, EmbeddingStatus.DIRTY, EmbeddingStatus.FAILED);

    private final CourseSearchDocumentRepository courseSearchDocumentRepository;
    private final CourseEmbeddingBatchProcessor batchProcessor;

    /**
     * 매일 새벽 4시 — INIT·DIRTY·FAILED 상태의 코스 문서를 일괄 재임베딩한다.
     * 장소 임베딩 배치(03:00)와 겹치지 않도록 04:00으로 설정.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void reembedStaleDocuments() {
        List<Long> targetCourseIds = courseSearchDocumentRepository.findCourseIdsByStatusIn(REEMBEDDING_TARGET_STATUSES);

        log.info("코스 재임베딩 배치 시작 - 대상 수: {}", targetCourseIds.size());
        embedInChunks(targetCourseIds);
        log.info("코스 재임베딩 배치 완료");
    }

    /**
     * courseId 목록을 EMBEDDING_CHUNK_SIZE 단위로 분할하여 순차 임베딩한다.
     * 청크 단위로 트랜잭션이 분리되어 있어, 한 청크가 실패해도 다른 청크에 영향을 주지 않는다.
     */
    private void embedInChunks(List<Long> courseIds) {
        List<List<Long>> chunks = splitIntoChunks(courseIds, EMBEDDING_CHUNK_SIZE);

        for (List<Long> chunk : chunks) {
            try {
                batchProcessor.processBatch(chunk);
            } catch (Exception e) {
                log.warn("코스 청크 임베딩 실패 — 해당 청크를 FAILED 처리합니다. size={}, error={}", chunk.size(), e.getMessage());
                batchProcessor.markChunkFailed(chunk);
            }
        }
    }

    /** 리스트를 maxSize 크기의 서브리스트 목록으로 분할한다. */
    private <T> List<List<T>> splitIntoChunks(List<T> list, int maxSize) {
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < list.size(); start += maxSize) {
            chunks.add(list.subList(start, Math.min(start + maxSize, list.size())));
        }
        return chunks;
    }
}
