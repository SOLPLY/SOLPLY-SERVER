package org.sopt.solply_server.domain.place.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.SortSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 정렬 스냅샷 재생성의 <b>진입점 하나</b>. 배치 훅과 어드민 훅이 같은 메서드를 부르고, 트랜잭션
 * 안이냐 밖이냐만 여기서 갈린다.
 *
 * <p><b>어드민 경로가 커밋을 기다려야 하는 이유.</b> {@code AdminPlaceService}의 쓰기는
 * {@code @Transactional} 안에서 일어나고, 스냅샷 로더는 <b>자기 커넥션으로 원본을 다시 읽는다</b>.
 * 커밋 전에 재생성하면 그 커넥션은 아직 커밋되지 않은 변경을 볼 수 없어 <em>옛 데이터</em>로 사진을
 * 짓고, 그 사진이 커밋된 새 상태를 덮어쓴 채 다음 트리거까지 남는다 — 방금 만든 장소가 목록에서
 * 사라지고 방금 옮긴 동네가 되돌아간 것처럼 보인다. 그래서 {@code afterCommit}으로 미룬다.
 *
 * <p><b>한 트랜잭션에 하나만 건다.</b> 어드민 요청 하나가 쓰기 경로를 여러 번 지나가도 재생성은
 * 회차당 한 번이면 족하다 — 두 번째 등록을 여기서 접지 않으면 같은 전량 스캔이 그 횟수만큼 돈다.
 *
 * <p><b>실패해도 호출자를 죽이지 않는다.</b> 빌드가 실패하면 직전 회차의 사진이 그대로 남고, 그
 * 사이 바뀐 장소는 <b>낡은 순서</b>로 보인다 — 그 창의 상한은 다음 트리거까지다. 어드민 요청이
 * 스냅샷 때문에 500이 되는 것보다 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceSortSnapshotRefresher {

    private final PlaceSortSnapshotLoader loader;
    private final PlaceListProperties placeListProperties;

    /**
     * 트랜잭션 안이면 커밋 뒤에, 밖이면 그 자리에서 스냅샷을 다시 짓는다.
     *
     * <p>{@code MEMORY}가 아니면 아무것도 하지 않는다 — 읽지 않는 스냅샷을 짓지 않는다는 규칙은
     * {@code PlaceListProperties.SortSource} javadoc에 있다.
     */
    public void refreshAfterCommit() {
        if (placeListProperties.getSortSource() != SortSource.MEMORY) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rebuildQuietly();
            return;
        }
        if (alreadyRegistered()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new SortSnapshotRefresh());
    }

    private boolean alreadyRegistered() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof SortSnapshotRefresh) {
                return true;
            }
        }
        return false;
    }

    private void rebuildQuietly() {
        try {
            loader.rebuild();
        } catch (Exception e) {
            log.error("장소 정렬 스냅샷 교체 실패 - 직전 회차 스냅샷을 유지한다", e);
        }
    }

    /** 등록 중복을 알아보기 위한 이름 있는 타입이다 — 람다로 두면 {@link #alreadyRegistered}가 못 센다 */
    private final class SortSnapshotRefresh implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            rebuildQuietly();
        }
    }
}
