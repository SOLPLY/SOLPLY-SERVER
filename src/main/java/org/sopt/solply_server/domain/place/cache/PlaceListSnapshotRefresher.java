package org.sopt.solply_server.domain.place.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 쓰기가 목록 사진을 다시 찍게 하는 <b>진입점 하나</b>. 트랜잭션 안이냐 밖이냐만 여기서
 * 갈리고, 하는 일은 어느 쪽이든 {@link PlaceListSnapshotLoader#rebuild()} 한 번이다 —
 * <b>부분 갱신은 없다</b>.
 *
 * <p><b>이 훅이 존재하는 이유는 즉시성이다.</b> 스냅샷을 다시 짓는 트리거는 셋인데 각자 맡은
 * 것이 다르다: 기동 빌드는 첫 사진을 세우고, 10분 타이머는 <b>배치가 채우는 통계</b>(카운트·점수)를
 * 화면으로 옮기며, 이 훅은 <b>어드민이 바꾼 콘텐츠</b>(장소의 생성·수정·삭제·재활성)를 그 자리에서
 * 반영한다. 통계가 10분 늦는 것은 수용한 창이지만 방금 등록한 장소가 10분간 안 보이는 것은 아니라는
 * 제품 결정이 이 클래스다. 그래서 SLA가 둘로 갈린다 — <b>어드민 변경은 커밋 직후(수백 ms),
 * 카운트·점수는 최대 10분.</b>
 *
 * <p><b>커밋 <em>뒤에</em> 짓는 것이 핵심이다.</b> 로더는 자기 커넥션으로 원본을 다시 읽는다.
 * 커밋 전에 지으면 그 커넥션은 아직 커밋되지 않은 변경을 볼 수 없어 <em>옛 데이터</em>로 사진을
 * 짓고, 그 사진이 커밋된 새 상태를 덮은 채 다음 트리거까지 남는다 — 방금 만든 장소가 목록에서
 * 사라지고 방금 옮긴 동네가 되돌아간 것처럼 보인다.
 *
 * <p><b>한 트랜잭션에 하나만 건다.</b> 어드민 요청 하나가 쓰기 경로를 여러 번 지나가도 재생성은
 * 회차당 한 번이면 족하다 — 두 번째 등록을 여기서 접지 않으면 같은 전량 스캔이 그 횟수만큼 돈다.
 *
 * <p><b>실패해도 호출자를 죽이지 않는다.</b> 빌드가 실패하면 직전 회차의 사진이 그대로 남고, 그
 * 사이 바뀐 장소는 <b>낡은 모습</b>으로 보인다 — 그 창의 상한은 다음 트리거(타이머)까지다. 어드민
 * 요청이 스냅샷 때문에 500이 되는 것보다 낫다.
 *
 * <p><b>한계 — 갱신되는 것은 요청을 받은 JVM 하나뿐이다.</b> 사진은 인스턴스의 힙에 있으므로, 다른
 * 인스턴스는 자기 타이머가 돌 때까지 옛 사진을 서빙한다. {@link PlaceListSnapshot}의 "버전은
 * 인스턴스 로컬"과 같은 전제 위에 서 있으니 <b>스케일아웃할 때 둘을 함께 되짚을 것.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceListSnapshotRefresher {

    private final PlaceListSnapshotLoader loader;

    /** 트랜잭션 안이면 커밋 뒤에, 밖이면 그 자리에서 사진을 다시 찍는다 */
    public void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rebuildQuietly();
            return;
        }
        if (alreadyRegistered()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new ListSnapshotRefresh());
    }

    private boolean alreadyRegistered() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof ListSnapshotRefresh) {
                return true;
            }
        }
        return false;
    }

    private void rebuildQuietly() {
        try {
            loader.rebuild();
        } catch (Exception e) {
            log.error("장소 목록 스냅샷 교체 실패 - 직전 회차 사진을 유지한다"
                    + "(다음 타이머 회차까지 낡은 값이 나간다)", e);
        }
    }

    /** 등록 중복을 알아보기 위한 이름 있는 타입이다 — 람다로 두면 {@link #alreadyRegistered}가 못 센다 */
    private final class ListSnapshotRefresh implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            rebuildQuietly();
        }
    }
}
