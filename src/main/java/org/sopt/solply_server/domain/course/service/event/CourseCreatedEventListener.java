package org.sopt.solply_server.domain.course.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CourseSearchDocument;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.repository.CourseSearchDocumentRepository;
import org.sopt.solply_server.domain.course.service.CourseEmbeddingBatchProcessor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseCreatedEventListener {

    private final CourseRepository courseRepository;
    private final CourseSearchDocumentRepository courseSearchDocumentRepository;
    private final CourseEmbeddingBatchProcessor batchProcessor;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(CourseCreatedEvent event) {
        Long courseId = event.courseId();

        Course course = courseRepository.findById(courseId).orElse(null);
        if (course == null) {
            log.warn("코스를 찾을 수 없습니다 - courseId={}", courseId);
            return;
        }

        // 비공개 코스는 추천 대상이 아니므로 임베딩 생략
        if (!course.isShared()) {
            log.debug("비공개 코스는 임베딩을 생략합니다 - courseId={}", courseId);
            return;
        }

        log.info("코스 임베딩 생성 시작 - courseId={}", courseId);

        CourseSearchDocument doc = courseSearchDocumentRepository.findById(courseId)
                .orElseGet(() -> courseSearchDocumentRepository.save(CourseSearchDocument.init(course)));

        batchProcessor.processOne(doc);
    }
}
