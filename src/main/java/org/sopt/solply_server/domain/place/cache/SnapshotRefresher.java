package org.sopt.solply_server.domain.place.cache;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.ProcessedMark;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.domain.place.cache.publication.StalePublicationBaseException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 수정을 커밋 뒤에 캐시에 반영하고 <b>발행까지 잇는다</b>.
 *
 * <p>예전에는 요청을 받은 인스턴스의 힙만 고쳤다. 그러면 다른 인스턴스와 새로 뜬 인스턴스가 그
 * 수정이 빠진 상태를 서빙한다 — 발행물이 생긴 지금은 그 수정도 공유 저장소에 올라가야 한다.
 *
 * <p><b>커밋 뒤 훅이 하는 일의 순서가 계약이다.</b>
 * <ol>
 *   <li>밀린 발행물을 먼저 설치한다 — 낡은 바탕 위에 패치하면 다른 인스턴스가 방금 올린 내용을
 *       내가 덮는다.</li>
 *   <li>바뀐 것만 다시 읽어 후보를 <b>순수하게 계산한다.</b> 홀더도 {@link SnapshotBox}도
 *       건드리지 않는다.</li>
 *   <li>발행한다.</li>
 *   <li><b>성공했을 때만</b> 공통 설치 경로로 내려받아 설치한다.</li>
 * </ol>
 *
 * <p><b>3이 4보다 앞인 것이 이 순서의 핵심이다.</b> 먼저 힙을 갈고 발행에 실패하면 그 인스턴스만
 * 공유되지 않은 더 높은 회차를 들게 되고, 뒤이어 내려오는 (더 낮거나 같은) 공유 발행물을 단조
 * 가드가 거절한다 — 그 인스턴스가 영영 다른 것을 서빙한다.
 *
 * <p><b>락 순서를 한 방향으로 못 박는다:</b> {@link CacheWriteLock} → (짧은 DB 트랜잭션).
 * DB 행 락을 든 채 {@code CacheWriteLock}을 잡는 경로가 이 클래스에도 발행자에도 없다.
 *
 * <p><b>실패하면 거짓 성공을 말하지 않는다.</b> 어디서 실패하든 예외를 삼키되 <b>요청을 닫지
 * 않으므로</b> 발행자가 원본에서 전량으로 되돌린다. 로그에 "갱신 완료"를 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotRefresher {

    private final SnapshotLoader loader;
    private final SnapshotInstaller installer;
    private final SnapshotPublisher publisher;
    private final SnapshotBox snapshotBox;
    private final PlaceViewHolder placeViewHolder;
    private final TagViewHolder tagViewHolder;
    private final CacheWriteLock writeLock;
    private final SnapshotPublicationService publicationService;

    /**
     * 어드민이 손댄 장소들 — 정렬 배열이 갈릴 수 있다.
     *
     * @param mySeq 어드민 <b>쓰기 트랜잭션 안에서</b> 받아 둔 요청 순번
     */
    public void refreshPlacesAfterCommit(Collection<Long> placeIds, long mySeq) {
        if (placeIds.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            patchPlaces(new LinkedHashSet<>(placeIds), mySeq);
            return;
        }
        registeredPatch(mySeq).add(placeIds);
    }

    /** 장소 한 건의 표시값만 — 회차는 이어받는다. */
    public void patchPlaceViewAfterCommit(long placeId, long mySeq) {
        runAfterCommit(() -> publishLocalChange(mySeq, held -> {
            Map<Long, PlaceView> views = new HashMap<>(placeViewHolder.all());
            loader.readView(placeId).ifPresent(view -> views.put(view.placeId(), view));
            return publisher.encode(held.version(), held.sortedPlaces().entries(),
                    views, tagViewHolder.all());
        }));
    }

    /** 태그 맵 전체 — 회차는 이어받는다. */
    public void refreshTagViewsAfterCommit(long mySeq) {
        runAfterCommit(() -> publishLocalChange(mySeq, held ->
                publisher.encode(held.version(), held.sortedPlaces().entries(),
                        placeViewHolder.all(), loader.readTagViews())));
    }

    private void patchPlaces(Collection<Long> placeIds, long mySeq) {
        publishLocalChange(mySeq, held -> {
            SnapshotLoader.SourceState changed = loader.readChangedState(placeIds);
            SortedPlaces patched = held.sortedPlaces().patch(placeIds, changed.entries());
            Map<Long, PlaceView> views = new HashMap<>(placeViewHolder.all());
            // 손댄 id를 먼저 비우는 것이 삭제를 반영하는 자리다 — 지운 장소는 changed.views()에
            // 없으므로, 비우지 않으면 배열에서 빠진 장소의 표시값만 payload에 남아 따라다닌다
            views.keySet().removeAll(placeIds);
            views.putAll(changed.views());
            // 배열이 그대로면 표시값만 바뀐 수정이다 — 회차를 이어받아 진행 중인 커서를 지킨다
            Long carried = patched == held.sortedPlaces() ? held.version() : null;
            return publisher.encode(carried, patched.entries(), views, tagViewHolder.all());
        });
    }

    /**
     * 훅 하나의 몸통. {@code compute}는 <b>순수 계산이어야 한다</b> — 홀더와 {@link SnapshotBox}를
     * 건드리면 이 클래스 javadoc의 순서 계약이 깨진다.
     */
    private void publishLocalChange(long mySeq, Function<Snapshot, PublicationCandidate> compute) {
        try {
            writeLock.run(() -> {
                installer.installIfChanged();                       // ①
                long base = installer.installedPublicationId();
                Snapshot held = snapshotBox.current();
                if (held == null || base < 0) {
                    log.warn("기동 복원 전이라 어드민 수정을 발행하지 못한다 - 요청(seq={})을"
                            + " 열어 둔다(발행자가 원본에서 짓는다)", mySeq);
                    return;
                }
                PublicationCandidate candidate = compute.apply(held);   // ②
                long publicationId = publicationService.publish(        // ③
                        candidate, base, ProcessedMark.solelyMine(mySeq));
                publicationService.cleanUpQuietly(publicationId);
                installer.installLatest();                              // ④
                log.info("어드민 수정 발행 - publication={}, structural={}, bytes={}, seq={}",
                        publicationId, candidate.isStructural(), candidate.payloadBytes(), mySeq);
            });
        } catch (StalePublicationBaseException e) {
            log.warn("어드민 수정 발행을 놓쳤다 - 그 사이 다른 발행이 있었다."
                    + " 요청(seq={})을 열어 둔다 - 발행자가 원본에서 되돌린다", mySeq);
        } catch (Exception e) {
            log.error("어드민 수정의 캐시 반영·발행 실패 - 요청(seq={})을 열어 둔다"
                    + "(발행자가 원본에서 되돌린다)", mySeq, e);
        }
    }

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

    private PlacePatch registeredPatch(long mySeq) {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PlacePatch patch) {
                return patch;
            }
        }
        PlacePatch patch = new PlacePatch(mySeq);
        TransactionSynchronizationManager.registerSynchronization(patch);
        return patch;
    }

    /**
     * 한 트랜잭션이 여러 번 불러도 커밋 뒤 패치는 한 번이다.
     *
     * <p>순번은 <b>맨 처음 등록한 것</b>을 쓴다. 한 트랜잭션이 두 번 불러 요청이 둘 올라갔다면
     * {@code requested_seq}가 그 순번보다 커져 훅이 요청을 닫지 못하고, 발행자가 원본에서 전량으로
     * 짓는다 — 닫히지 않는 쪽이 안전한 방향이다.
     */
    private final class PlacePatch implements TransactionSynchronization {
        private final Set<Long> placeIds = new LinkedHashSet<>();
        private final long mySeq;

        private PlacePatch(long mySeq) {
            this.mySeq = mySeq;
        }

        private void add(Collection<Long> ids) {
            placeIds.addAll(ids);
        }

        @Override
        public void afterCommit() {
            patchPlaces(placeIds, mySeq);
        }
    }
}
