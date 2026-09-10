package org.sopt.solply_server.domain.place.cache;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
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
 *   <li><b>장소 부분 패치</b>({@link #refreshPlacesAfterCommit(Collection)}) — 장소의 생성·수정·
 *       삭제·재활성이다. 손댄 장소 id를 모아 넘기면 로더가 그 행들만 다시 읽어 정렬 배열의 그
 *       자리만 갈고 표시값도 함께 갱신한다({@link SnapshotLoader#patch}). <b>어드민 쓰기는 전량
 *       재빌드를 부르지 않는다</b> — 장소 하나를 고치자고 전 장소를 다시 읽는 것이 이 경로가
 *       없애려는 비용이다.</li>
 *   <li><b>장소 표시값 패치</b>({@link #patchPlaceViewAfterCommit(long)}) — 이미지 파일 키가
 *       스테이징에서 최종으로 갈리는 비동기 후처리 하나다({@code PlaceImageFieldUpdater}).
 *       썸네일은 <b>정의상</b> 순서·소속·필터에 닿지 않으므로 정렬 배열을 쳐다볼 일이 없고, 그래서
 *       위 경로가 아니라 홀더 한 항목만 갈아 끼우는 이 경로로 남는다.</li>
 *   <li><b>태그 맵 다시 읽기</b>({@link #refreshTagViewsAfterCommit()}) — 태그 이름·활성 수정이다.
 *       태그는 수십 행이라 <b>어느 태그가 바뀌었는지 모으지 않고</b> 맵을 통째로 다시 읽는다.</li>
 * </ul>
 *
 * <p><b>장소 부분 패치만 트랜잭션당 하나로 접는다.</b> 어드민 요청 하나가 쓰기 경로를 여러 번
 * 지나가는 경로가 실제로 있고, 접으면 그 요청이 손댄 장소 <b>전부</b>가 id 하나의 집합으로 모여
 * 문장 한 번·회차 한 번으로 끝난다. 동네를 되살리는 요청처럼 수백 건을 한 번에 손대는 경로가 특히
 * 그렇다. 같은 id가 여러 번 들어와도 집합이 접으므로 같은 행을 두 번 읽지 않는다. 나머지 둘은
 * 접지 않는다 — 한 트랜잭션에서 같은 훅이 두 번 불리는 경로가 지금 없고, 불려도 같은 값을 두 번
 * 넣을 뿐이다.
 *
 * <p><b>이 훅이 존재하는 이유는 즉시성이다.</b> 캐시를 고치는 트리거는 셋인데 각자 맡은
 * 것이 다르다: 기동 빌드는 첫 스냅샷을 세우고, 주기 타이머는 <b>배치가 채우는 정렬 키</b>(카운트·
 * 점수)를 화면으로 옮기며, 이 훅은 <b>어드민이 바꾼 콘텐츠</b>를 그 자리에서 반영한다. 통계가 한
 * 회차 늦는 것은 수용한 창이지만 방금 등록한 장소가 그만큼 안 보이는 것은 아니라는 제품 결정이 이
 * 클래스다. 그래서 반영 시점이 둘로 갈린다 — <b>어드민 변경은 커밋 직후, 카운트·점수는 다음 성공한
 * 전량 재빌드.</b>
 *
 * <p><b>커밋 <em>뒤에</em> 읽는 것이 핵심이다.</b> 로더는 자기 커넥션으로 원본을 다시 읽는다.
 * 커밋 전에 읽으면 그 커넥션은 아직 커밋되지 않은 변경을 볼 수 없어 <em>옛 데이터</em>를 담고,
 * 그것이 커밋된 새 상태를 덮은 채 다음 트리거까지 남는다 — 방금 만든 장소가 목록에서
 * 사라지고 방금 옮긴 동네가 되돌아간 것처럼 보인다. <b>롤백된 트랜잭션에서는 아무것도 돌지
 * 않는다</b> — {@code afterCommit}은 커밋에만 불린다.
 *
 * <p><b>쓰기는 전부 {@link CacheWriteLock} 안에서 한다.</b> 부분 패치와 전량 재빌드가 겹치면
 * 재빌드가 읽어 둔 옛 값이 방금 패치한 값을 덮을 수 있다 — 근거는 그쪽 javadoc.
 *
 * <p><b>실패해도 호출자를 죽이지 않는다.</b> 갱신이 실패하면 직전 값이 그대로 남고, 그
 * 사이 바뀐 장소는 <b>낡은 모습</b>으로 보인다 — 되돌리는 것은 <b>다음 성공한 전량 재빌드</b>이며,
 * 그것이 {@code place_stats}를 통째로 다시 읽으므로 빠뜨린 갱신이 그 회차에 함께 실린다. 어드민
 * 요청이 캐시 때문에 500이 되는 것보다 낫다. 격리는 훅마다 따로 걸린다.
 *
 * <p><b>한계 — 갱신되는 것은 요청을 받은 JVM 하나뿐이다.</b> 스냅샷도 표시값도 인스턴스의 힙에
 * 있으므로, 다른 인스턴스는 자기 타이머가 돌 때까지 옛 값을 서빙한다. {@link SnapshotBox}의
 * "버전은 인스턴스 로컬"과 같은 전제 위에 서 있으니 <b>스케일아웃할 때 둘을 함께 되짚을 것.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotRefresher {

    private final SnapshotLoader loader;
    private final PlaceViewHolder placeViewHolder;
    private final TagViewHolder tagViewHolder;
    private final CacheWriteLock writeLock;

    /**
     * 어드민이 손댄 장소들 — 커밋 뒤에 그 행들만 다시 읽어 정렬 배열의 그 자리와 표시값을 간다.
     *
     * <p><b>id는 트랜잭션 하나로 모인다.</b> 같은 트랜잭션에서 여러 번 불리면 앞서 건 동기화의
     * 집합에 더할 뿐이고, 커밋 뒤에 그 집합 하나로 패치가 한 번 돈다. 중복 id는 집합이 접는다.
     *
     * <p><b>넘기는 id는 "손댔다"는 사실만 뜻한다.</b> 그 수정이 정렬 배열에 닿는지는 여기서도
     * 서비스에서도 판정하지 않는다 — 최신 행과 이 회차의 엔트리를 견주는
     * {@link SortedPlaces#patch}가 그 판정의 유일한 주인이고, 닿지 않았으면 회차를 쓰지 않는다.
     * 판정을 호출부로 흩으면 "무엇이 배열에 닿는 값인가"가 두 곳에 적히고, 값이 하나 늘 때
     * 한쪽만 고쳐진다.
     */
    public void refreshPlacesAfterCommit(Collection<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            patchQuietly(new LinkedHashSet<>(placeIds));
            return;
        }
        registeredPatch().add(placeIds);
    }

    /**
     * 순서·소속·필터에 닿을 수 없는 장소 수정 — 커밋 뒤에 그 장소의 표시값만 다시 읽어 갈아 끼운다.
     * 지금 여기로 오는 것은 이미지 파일 키의 비동기 후처리 하나뿐이다
     * ({@code PlaceImageFieldUpdater}).
     *
     * <p>장소 행이 사라졌으면 아무것도 하지 않는다(맵에 남은 옛 값은 배열에서 빠지면 안 보인다).
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
     * 여러 태그를 건드리는 경로에서 하나를 빠뜨리면 그 태그는 다음 성공한 전량 재빌드까지 옛
     * 이름·옛 활성으로 나가면서 아무 오류도 내지 않는다. 값이 아니라 DB를 락 안에서 다시 읽으므로
     * 늦게 깨어난 훅이 남의 최신 값을 옛 값으로 덮는 창도 없다.
     *
     * <p><b>태그 맵은 회차 스냅샷 밖에 산다</b>({@link TagViewHolder}) — 그래서 태그 수정은 정렬
     * 배열을 건드리지 않고 회차도 쓰지 않는다. 태그를 <b>장소에서 떼고 붙이는</b> 수정은 반대로
     * 필터 결과가 갈리므로 장소 경로({@link #refreshPlacesAfterCommit})로 간다.
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

    /** 이 트랜잭션의 장소 패치 동기화 — 없으면 걸고, 있으면 그것에 id를 보탠다 */
    private PlacePatch registeredPatch() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PlacePatch patch) {
                return patch;
            }
        }
        PlacePatch patch = new PlacePatch();
        TransactionSynchronizationManager.registerSynchronization(patch);
        return patch;
    }

    private void patchQuietly(Collection<Long> placeIds) {
        // 락은 로더가 잡는다 — 읽기 트랜잭션을 락 안에서 열어야 하므로 그 순서의 주인이 그쪽이다
        runQuietly(() -> loader.patch(placeIds));
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
                    + "(다음 성공한 전량 재빌드가 place_stats에서 되돌린다)", e);
        }
    }

    /**
     * 한 트랜잭션이 손댄 장소 id를 모으는 동기화. 이름 있는 타입인 것은 {@link #registeredPatch}가
     * <b>자기가 건 것을 찾아</b> id를 보태야 하기 때문이다 — 익명 클래스로는 찾을 수 없다.
     *
     * <p>집합을 채우는 것은 쓰기 트랜잭션 스레드 하나뿐이고, 다 채워진 뒤에야 같은 스레드가
     * {@code afterCommit}에서 읽는다. 동기화 객체 자체가 스레드에 묶여 있으므로 여기에 동기화를
     * 걸 자리가 아니다.
     */
    private final class PlacePatch implements TransactionSynchronization {

        /** 순서를 지키는 집합이다 — 중복은 접히고, 로그와 {@code IN} 목록은 부른 순서대로 남는다 */
        private final Set<Long> placeIds = new LinkedHashSet<>();

        private void add(Collection<Long> ids) {
            placeIds.addAll(ids);
        }

        @Override
        public void afterCommit() {
            patchQuietly(placeIds);
        }
    }
}
