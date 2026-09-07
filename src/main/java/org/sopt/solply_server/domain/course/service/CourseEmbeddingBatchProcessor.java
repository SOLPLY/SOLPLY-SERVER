package org.sopt.solply_server.domain.course.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CourseSearchDocument;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.repository.CourseSearchDocumentRepository;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseEmbeddingBatchProcessor {

    private final CourseSearchDocumentRepository courseSearchDocumentRepository;
    private final CourseRepository courseRepository;
    private final CourseRetrievalTextBuilder retrievalTextBuilder;
    private final EmbeddingService embeddingService;

    /**
     * 단건 임베딩. 호출자의 트랜잭션 내에서 동작한다.
     */
    public void processOne(CourseSearchDocument doc) {
        List<Course> courses = courseRepository.findByIdInWithAllForRecommendation(List.of(doc.getCourseId()));
        if (courses.isEmpty()) {
            log.warn("코스를 찾을 수 없습니다 - courseId={}", doc.getCourseId());
            doc.markFailed();
            return;
        }
        Course course = courses.get(0);

        try {
            String retrievalText = retrievalTextBuilder.build(course);
            float[] embedding = embeddingService.embed(retrievalText);
            doc.updateEmbedding(retrievalText, embedding, embeddingService.getModelName());
            log.info("코스 임베딩 완료 - courseId={}", doc.getCourseId());
        } catch (Exception e) {
            doc.markFailed();
            log.warn("코스 임베딩 실패 - courseId={}, error={}", doc.getCourseId(), e.getMessage());
        }
    }

    @Transactional
    public void processBatch(List<Long> courseIds) {
        Map<Long, Course> courseById = courseRepository.findByIdInWithAllForRecommendation(courseIds)
                .stream().collect(Collectors.toMap(Course::getId, c -> c));

        List<CourseSearchDocument> docs = courseSearchDocumentRepository.findAllById(courseIds);

        List<CourseSearchDocument> embeddableDocs = new ArrayList<>();
        List<String> retrievalTexts = new ArrayList<>();

        for (CourseSearchDocument doc : docs) {
            Course course = courseById.get(doc.getCourseId());
            if (course == null) {
                log.warn("코스를 찾을 수 없어 건너뜁니다 - courseId={}", doc.getCourseId());
                continue;
            }
            embeddableDocs.add(doc);
            retrievalTexts.add(retrievalTextBuilder.build(course));
        }

        if (embeddableDocs.isEmpty()) return;

        List<float[]> embeddings = embeddingService.embedAll(retrievalTexts);

        for (int i = 0; i < embeddableDocs.size(); i++) {
            embeddableDocs.get(i).updateEmbedding(retrievalTexts.get(i), embeddings.get(i), embeddingService.getModelName());
        }

        log.info("코스 배치 임베딩 완료 - count={}", embeddableDocs.size());
    }

    @Transactional
    public void markChunkFailed(List<Long> courseIds) {
        List<CourseSearchDocument> docs = courseSearchDocumentRepository.findAllById(courseIds);
        docs.forEach(CourseSearchDocument::markFailed);
        log.warn("코스 청크 임베딩 실패로 FAILED 처리 - count={}", docs.size());
    }
}
