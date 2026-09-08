package org.sopt.solply_server.domain.place.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 쓰기가 목록 캐시를 갱신하게 하는 <b>진입점 하나</b>. 트랜잭션 안이냐 밖이냐만 여기서
 * 갈리고, 하는 일은 어느 쪽이든 같다.
 *
 * <p><b>갱신은 세 가지다.</b>
 * <ul>
 *   <li><b>전량 재빌드</b>({@link #refreshAfterCommit()}) — 소속·정렬·필터가 바뀌는 수정이다.
 *       장소의 생성·삭제·동네 이동·태그 부착처럼 <em>어느 배열에 서는가</em>가 달라지면 사진을
 *       통째로 다시 찍어야 한다.</li>
 *   <li><b>장소 표시값 패치</b>({@link #patchPlaceViewAfterCommit(long)}) — 이름·썸네일처럼 순서에
 *       닿지 않는 수정이다. 그 장소 하나만 홀더에서 갈아 끼우므로 전량 스캔이 돌지 않는다.</li>
 *   <li><b>태그 맵 다시 읽기</b>({@link #refreshTagViewsAfterCommit()}) — 태그 이름·활성 수정이다.
 *       태그는 수십 행이라 <b>어느 태그가 바뀌었는지 모으지 않고</b> 맵을 통째로 다시 읽는다.</li>
 * </ul>
 *
 * <p><b>훅 셋은 서로 독립이고 병합하지 않는다.</b> 각자 자기 동기화를 걸고 커밋 뒤에 각자 돈다.
 * 전량 재빌드만 트랜잭션당 한 번으로 접는데, 그것은 어드민 요청 하나가 쓰기 경로를 여러 번
 * 지나가는 경로가 실제로 있어서다. 나머지 둘은 접지 않는다 — 한 트랜잭션에서 같은 훅이 두 번
 * 불리는 경로가 지금 없고, 불려도 같은 값을 두 번 넣을 뿐이다. 전량과 패치가 함께 걸리는 경로도
 * 지금은 없다. 걸리더라도 둘 다 도는 것이 계약이다(패치 하나가 헛도는 정도).
 *
 * <p><b>이 훅이 존재하는 이유는 즉시성이다.</b> 캐시를 고치는 트리거는 셋인데 각자 맡은
 * 것이 다르다: 기동 빌드는 첫 사진을 세우고, 10분 타이머는 <b>배치가 채우는 통계</b>(카운트·점수)를
 * 화면으로 옮기며, 이 훅은 <b>어드민이 바꾼 콘텐츠</b>를 그 자리에서 반영한다. 통계가 10분 늦는
 * 것은 수용한 창이지만 방금 등록한 장소가 10분간 안 보이는 것은 아니라는 제품 결정이 이
 * 클래스다. 그래서 SLA가 둘로 갈린다 — <b>어드민 변경은 커밋 직후(수백 ms),
 * 카운트·점수는 최대 10분.</b>
 *
 * <p><b>커밋 <em>뒤에</em> 읽는 것이 핵심이다.</b> 로더는 자기 커넥션으로 원본을 다시 읽는다.
 * 커밋 전에 읽으면 그 커넥션은 아직 커밋되지 않은 변경을 볼 수 없어 <em>옛 데이터</em>를 담고,
 * 그것이 커밋된 새 상태를 덮은 채 다음 트리거까지 남는다 — 방금 만든 장소가 목록에서
 * 사라지고 방금 옮긴 동네가 되돌아간 것처럼 보인다.
 *
 * <p><b>쓰기는 전부 {@link PlaceListWriteLock} 안에서 한다.</b> 재빌드와 패치가 겹치면 재빌드가
 * 읽어 둔 옛 값이 방금 패치한 값을 덮을 수 있다 — 근거는 그쪽 javadoc.
 *
 * <p><b>실패해도 호출자를 죽이지 않는다.</b> 갱신이 실패하면 직전 값이 그대로 남고, 그
 * 사이 바뀐 장소는 <b>낡은 모습</b>으로 보인다 — 그 창의 상한은 다음 트리거(타이머)까지다. 어드민
 * 요청이 캐시 때문에 500이 되는 것보다 낫다. 격리는 훅마다 따로 걸린다.
 *
 * <p><b>한계 — 갱신되는 것은 요청을 받은 JVM 하나뿐이다.</b> 사진도 표시값도 인스턴스의 힙에
 * 있으므로, 다른 인스턴스는 자기 타이머가 돌 때까지 옛 값을 서빙한다. {@link PlaceListSnapshot}의
 * "버전은 인스턴스 로컬"과 같은 전제 위에 서 있으니 <b>스케일아웃할 때 둘을 함께 되짚을 것.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceListSnapshotRefresher {

    private final PlaceListSnapshotLoader loader;
    private final PlaceViewHolder placeViewHolder;
    private final TagViewHolder tagViewHolder;
    private final PlaceListWriteLock writeLock;

    /** 순서가 바뀌는 수정 — 커밋 뒤에 사진과 표시값을 통째로 다시 짓는다 */
    public void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rebuildQuietly();
            return;
        }
        if (rebuildAlreadyRegistered()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new ListSnapshotRefresh());
    }

    /**
     * 순서에 닿지 않는 장소 수정 — 커밋 뒤에 그 장소의 표시값만 다시 읽어 갈아 끼운다.
     * 장소 행이 사라졌으면 아무것도 하지 않는다(맵에 남은 옛 값은 배열에서 빠지면 안 보인다).
     */
    public void patchPlaceViewAfterCommit(long placeId) {
        runAfterCommit(() -> patchPlaceViewQuietly(placeId));
    }

    /**
     * 태그 이름·활성 수정 — 커밋 뒤에 태그 맵을 통째로 다시 읽어 교체한다. 그 태그를 단 장소들을
     * 찾아다니지 않는 것이 이 구조의 요점이다(대표 태그 이름은 조회 시점에 합쳐진다).
     *
     * <p><b>어느 태그가 바뀌었는지 받지 않는다.</b> 태그는 수십 행이라 전량 읽기가 단건 읽기와
     * 사실상 같은 값이고, 대신 <em>세는 일</em>이 통째로 사라진다 — 비활성 캐스케이드처럼 한 요청이
     * 여러 태그를 건드리는 경로에서 하나를 빠뜨리면 그 태그는 다음 타이머 회차(≤10분)까지 옛
     * 이름·옛 활성으로 나가면서 아무 오류도 내지 않는다. 값이 아니라 DB를 락 안에서 다시 읽으므로
     * 늦게 깨어난 훅이 남의 최신 값을 옛 값으로 덮는 창도 없다.
     */
    public void refreshTagViewsAfterCommit() {
        runAfterCommit(this::refreshTagViewsQuietly);
    }

    /** 트랜잭션 안이면 커밋 뒤로, 밖이면 그 자리에서 — 표시값 훅 둘이 공유하는 분기 */
    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    private boolean rebuildAlreadyRegistered() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof ListSnapshotRefresh) {
                return true;
            }
        }
        return false;
    }

    private void rebuildQuietly() {
        runQuietly(loader::rebuild);
    }

    private void patchPlaceViewQuietly(long placeId) {
        // 읽기도 락 안이다 — 재빌드가 교체를 끝낸 뒤에 읽어야 그 뒤에 덮이지 않는다
        runQuietly(() -> writeLock.run(
                () -> loader.readView(placeId).ifPresent(placeViewHolder::put)));
    }

    private void refreshTagViewsQuietly() {
        runQuietly(() -> writeLock.run(() -> tagViewHolder.replaceAll(loader.readTagViews())));
    }

    /**
     * 실패는 로그만 남긴다 — 커밋 뒤 경로에서 예외가 새면 트랜잭션은 이미 커밋된 뒤라 롤백도
     * 되지 않는 채 오류만 나간다.
     */
    private void runQuietly(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("장소 목록 캐시 갱신 실패 - 직전 값을 유지한다"
                    + "(다음 타이머 회차까지 낡은 값이 나간다)", e);
        }
    }

    /**
     * 전량 재빌드가 트랜잭션당 하나인지 알아보기 위한 이름 있는 타입이다 — 익명 클래스로 두면
     * {@link #rebuildAlreadyRegistered}가 자기가 건 것을 찾지 못한다.
     */
    private final class ListSnapshotRefresh implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            rebuildQuietly();
        }
    }
}
