package org.sopt.solply_server.domain.place.cache.publication;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행 트랜잭션을 소유하는 유일한 자리.
 *
 * <p><b>넷이 한 트랜잭션이라야 한다.</b> payload INSERT · 회차 확정 · 포인터 CAS · 처리 표시를
 * 나누면 셋이 생긴다 — CAS에 진 발행물이 고아 행으로 남고, 발행은 됐는데 표시가 안 된 창(다음
 * 회차가 헛빌드)이나 표시만 되고 발행이 안 된 창(요청은 사라졌는데 내용은 안 올라감)이 열린다.
 * 그래서 리포지토리의 쓰기 메서드에는 propagation을 달지 않고 여기 참여하게 한다.
 *
 * <p><b>{@code REQUIRES_NEW}인 이유는 어드민 경로다.</b> 어드민 훅은 커밋 뒤
 * {@code afterCommit}에서 도는데, 그 시점에도 완료된 트랜잭션의 자원이 스레드에 묶여 있다.
 * {@code REQUIRED}로 두면 그 끝난 트랜잭션에 다시 붙어 커밋되지 않는다.
 *
 * <p><b>락 순서</b>는 언제나 {@code place_list_publications} → {@code pointer} →
 * {@code place_list_rebuild_requests}다. 발행자와 어드민 훅이 같은 순서를 쓰고, 통계·어드민
 * 쓰기 트랜잭션은 {@code rebuild_requests}를 <b>자기 마지막 쓰기로</b> 잡는다
 * ({@link SnapshotRebuildRequestRepository#request()}가 먼저 flush하는 이유). 그래서 이 셋을
 * 걸친 순환이 없다. 정리({@code deleteOlderThan})는 {@code publications}의 옛 행만 건드리고
 * 포인터를 FK로 읽기만 하므로, 발행을 기다릴 수는 있어도 발행이 정리를 기다리지는 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotPublicationService {

    private final SnapshotPublicationRepository publicationRepository;
    private final SnapshotRebuildRequestRepository requestRepository;

    /**
     * @param basePublicationId 후보를 <b>짓기 전에</b> 읽어 둔 현재 발행 id. 미발행이면 {@code null}
     * @return 새로 발행된 id
     * @throws StalePublicationBaseException 짓는 동안 다른 발행이 있었다 — 트랜잭션이 롤백된다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public long publish(PublicationCandidate candidate, Long basePublicationId, ProcessedMark mark) {
        long newId = publicationRepository.insertPayload(candidate);
        if (candidate.isStructural()) {
            publicationRepository.fillCursorVersionFromId(newId);
        }
        if (!publicationRepository.casPointer(newId, basePublicationId)) {
            // 던져서 트랜잭션을 통째로 되돌린다 — 방금 넣은 payload 행도 없던 일이 된다
            throw new StalePublicationBaseException(basePublicationId, newId);
        }
        mark.apply(requestRepository);
        return newId;
    }

    /**
     * 발행 뒤 옛 행을 지운다. <b>발행 트랜잭션 밖이고, 실패해도 발행을 실패로 만들지 않는다</b> —
     * 이미 커밋된 발행을 되돌릴 이유가 없고, 남은 행은 다음 회차의 정리가 다시 가져간다.
     */
    public void cleanUpQuietly(long currentPublicationId) {
        try {
            int deleted = publicationRepository.deleteOlderThan(currentPublicationId);
            if (deleted > 0) {
                log.info("옛 발행물 정리 - deleted={}, current={}", deleted, currentPublicationId);
            }
        } catch (Exception e) {
            log.warn("옛 발행물 정리 실패 - 발행은 이미 끝났고 다음 회차가 다시 지운다(current={})",
                    currentPublicationId, e);
        }
    }
}
