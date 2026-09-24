package org.sopt.solply_server.domain.place.cache.town;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 번호를 올린 쓰기가 <b>커밋된 뒤</b> 그 동네들의 새 번호를 공유 사본으로 미리 싣는다.
 *
 * <ul>
 *   <li>쓰기 트랜잭션 안에서는 동네 id만 모은다. 한 트랜잭션이 여러 번 불러도 작업은 하나다.
 *   <li>커밋 뒤 비동기 작업 하나를 올리고 곧바로 돌아간다 — 커밋 스레드에서 원본을 다시 읽거나
 *       저장소 I/O를 하지 않는다. 롤백이면 아무것도 하지 않는다. 제출이 실패해도 이미 성공한
 *       쓰기를 실패로 바꾸지 않는다.
 *   <li>작업은 새 REPEATABLE READ 트랜잭션에서 번호와 원본을 <b>함께</b> 읽는다
 *       ({@link TownSourceLoader#load}). 그 사이 다음 변경이 커밋됐으면 <b>실제로 읽은 번호</b>로
 *       싣는다 — 커밋한 번호를 붙여 지금 데이터를 싣지 않는다.
 * </ul>
 *
 * <p>프로세스가 내려가 작업이 사라지면 그 번호는 다음 조회의 DB 폴백이 싣는다. 영속 outbox나
 * 서버 사이 중복 방지는 두지 않는다 — 두 서버가 같은 번호를 실어도 SET NX라 먼저 실은 쪽이 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TownCommitPublisher {

    private final TownSourceLoader loader;
    private final TownVersionRepository versionRepository;
    private final TownRedisPublisher publisher;

    /** 이 동네들을 이번 트랜잭션의 커밋 뒤 발행 대상에 넣는다. */
    public void afterCommit(Collection<Long> townIds) {
        if (townIds.isEmpty() || !publisher.enabled()) {
            return;
        }
        Batch batch = currentBatch();
        if (batch != null) {
            batch.townIds.addAll(townIds);
        }
    }

    /** 처리 대상 전 동네(장소가 있는 동네 전부)를 커밋 뒤 발행 대상으로 둔다. */
    public void afterCommitAll() {
        if (!publisher.enabled()) {
            return;
        }
        Batch batch = currentBatch();
        if (batch != null) {
            batch.all = true;
        }
    }

    /** 이번 트랜잭션에 걸어 둔 모음. 트랜잭션이 없으면 발행할 커밋도 없으므로 {@code null}. */
    private Batch currentBatch() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 밖의 번호 변경이라 커밋 뒤 발행을 걸 수 없다");
            return null;
        }
        // 등록 목록은 지금 트랜잭션의 것이다 — REQUIRES_NEW 안쪽은 바깥과 섞이지 않는다
        for (TransactionSynchronization sync
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof Batch batch && batch.owner() == this) {
                return batch;
            }
        }
        Batch batch = new Batch();
        TransactionSynchronizationManager.registerSynchronization(batch);
        return batch;
    }

    /** 커밋 뒤 실행기에서 돈다. 검증이 직접 부를 수 있게 열어 둔다. */
    void publishCommitted(Set<Long> townIds, boolean all) {
        try {
            List<Long> targets = all
                    ? versionRepository.townIdsWithPlaces()
                    : List.copyOf(townIds);
            if (targets.isEmpty()) {
                return;
            }
            for (TownPlaces places : loader.load(targets)) {
                publisher.publishNow(places);
            }
        } catch (RuntimeException e) {
            log.warn("커밋 뒤 동네 공유 사본 발행 실패 - towns={}, all={}", townIds, all, e);
        }
    }

    private final class Batch implements TransactionSynchronization {

        private final Set<Long> townIds = new LinkedHashSet<>();
        private boolean all;

        private TownCommitPublisher owner() {
            return TownCommitPublisher.this;
        }

        @Override
        public void afterCommit() {
            Set<Long> towns = Set.copyOf(townIds);
            boolean everything = all;
            try {
                publisher.execute(() -> publishCommitted(towns, everything));
            } catch (RuntimeException e) {
                log.warn("커밋 뒤 동네 공유 사본 발행을 올리지 못했다 - towns={}, all={}",
                        towns, everything, e);
            }
        }
    }
}
