package org.sopt.solply_server.domain.place.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.service.event.PlaceBookmarkCreatedEvent;
import org.sopt.solply_server.domain.bookmark.service.event.PlaceBookmarkDeletedEvent;
import org.sopt.solply_server.domain.place.repository.PlaceStatsRepository;
import org.sopt.solply_server.domain.review.service.event.PlaceReviewCreatedEvent;
import org.sopt.solply_server.domain.review.service.event.PlaceReviewDeletedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * place_stats 카운트의 준실시간 증분 (설계 §2.5 재검토 · §2.8 사다리 ②단).
 *
 * <p><b>at-most-once — 실패는 삼키고 재시도하지 않는다.</b> 재시도는 중복 +2를 들여오는데,
 * 유실은 어차피 다음 02:00 배치가 전량 재대사한다. 실패 로그는 관측용이지 복구 신호가 아니다.
 * 여기에 {@code @Retryable}이나 outbox를 붙이려는 충동이 들면 먼저 설계 §2.5 재검토를 읽을 것 —
 * 재시도를 붙이는 순간 멱등 키 관리가 따라오고, 그건 이 기능이 사려던 것이 아니다.
 *
 * <p>AFTER_COMMIT인 이유: 원본 트랜잭션이 롤백되면 증분도 없어야 한다. REQUIRES_NEW인 이유:
 * AFTER_COMMIT 시점엔 원본 트랜잭션이 커밋 완료라 쓰기에 새 트랜잭션이 필요하다.
 * 격리는 기본(RR)로 둔다 — 단일 행 PK 쓰기라 배치의 RC 논거(대량 소스 스캔 락)가 해당 없다.
 *
 * <p>{@code @Async}인 이유: 카운트는 파생 통계이고 북마크·리뷰는 원본 행위다. 파생 쪽 사정
 * (행 락 대기·DB 지연)이 사용자의 응답 시간에 전이되면 우선순위가 역전된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceStatsIncrementListener {

    private final PlaceStatsRepository placeStatsRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PlaceBookmarkCreatedEvent event) {
        try {
            placeStatsRepository.incrementBookmark(event.placeId());
        } catch (Exception e) {
            log.warn("북마크 증분 실패 — 다음 배치가 재대사한다. placeId={}, error={}",
                    event.placeId(), e.getMessage());
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PlaceBookmarkDeletedEvent event) {
        try {
            placeStatsRepository.decrementBookmark(event.placeId());
        } catch (Exception e) {
            log.warn("북마크 감분 실패 — 다음 배치가 재대사한다. placeId={}, error={}",
                    event.placeId(), e.getMessage());
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PlaceReviewCreatedEvent event) {
        try {
            placeStatsRepository.incrementReview(event.placeId());
        } catch (Exception e) {
            log.warn("리뷰 증분 실패 — 다음 배치가 재대사한다. placeId={}, error={}",
                    event.placeId(), e.getMessage());
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PlaceReviewDeletedEvent event) {
        try {
            placeStatsRepository.decrementReview(event.placeId());
        } catch (Exception e) {
            log.warn("리뷰 감분 실패 — 다음 배치가 재대사한다. placeId={}, error={}",
                    event.placeId(), e.getMessage());
        }
    }
}
