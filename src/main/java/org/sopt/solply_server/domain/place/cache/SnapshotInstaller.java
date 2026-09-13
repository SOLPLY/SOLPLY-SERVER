package org.sopt.solply_server.domain.place.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.springframework.stereotype.Component;

/**
 * 원본을 읽어 지은 스냅샷 한 벌을 <b>원자적으로</b> 갈아 끼우는 자리. 조회 경로가 보는 세 가지
 * (정렬 배열·장소 표시값·태그 표시값)가 여기서 바뀐다.
 *
 * <p><b>실패해도 기존 상태가 남는다.</b> 읽기·정렬·객체 구성은 전부 락 밖에서 끝내고, 락 안에서는
 * 이미 완성된 것을 대입만 한다. 그래서 중간에 터지면 아무것도 바뀌지 않은 채로 끝나고, 지금 회차가
 * 그대로 서빙된다. 다음 폴이 다시 시도한다.
 *
 * <p><b>설치한 상태는 따로 들고 있지 않다.</b> "지금 어느 시점을 싣고 있나"의 답은 언제나
 * {@link SnapshotBox#current()}가 들고 있는 불변 {@link Snapshot} 하나다. 번호를 별도 필드로
 * 복제해 두면 요청이 상자와 그 필드를 따로 읽어 <b>서로 다른 설치 상태</b>를 볼 수 있다.
 *
 * <p><b>단조 가드는 {@code revision}이다.</b> 읽어 온 revision이 이미 설치한 것보다 크지 않으면
 * 설치하지 않는다 — 느린 리빌드가 늦게 도착해 새 회차를 되감는 것을 막는 유일한 자리다.
 *
 * <p><b>표시값 즉시 패치와의 조정 — 이 클래스에서 가장 미묘한 부분이다.</b> 어드민 수정은 커밋
 * 직후 {@link SnapshotViewPatcher}가 홀더에 곧바로 얹는다(리빌드를 기다리지 않는다). 그런데
 * 리빌드는 <b>그 커밋보다 앞선 시점</b>의 원본을 읽고 있었을 수 있고, 그 결과를 통째로 대입하면
 * 방금 얹은 값이 옛 값으로 되돌아간다.
 *
 * <p>그래서 얹은 패치는 <b>자기가 관측한 revision과 함께 보관</b>했다가, 설치가 읽어 온 revision
 * 보다 새 것이면 <b>새 맵을 홀더에 걸기 전에</b> 그 맵 위에 미리 얹는다. 낡은 것(설치가 이미 싣고
 * 온 것)은 그때 버린다. <b>홀더를 먼저 갈고 나중에 패치를 다시 얹는 순서는 안 된다</b> — 조회는
 * 락을 잡지 않으므로 그 사이에 옛 표시값이 그대로 보인다.
 *
 * <p><b>패치가 설치 시점을 올리지 않는 것이 핵심이다.</b> 올리면 그 번호까지의 리빌드가 전부
 * "이미 설치했다"로 막히는데, 그 사이 번호에는 표시값이 아닌 변경(집계 회차의 정렬 키, 다른
 * 어드민의 소속 변경)이 섞여 있다. 패치는 자기가 아는 한 칸만 고치고 번호는 건드리지 않는다 —
 * 번호가 뜻하는 것은 "이 시점의 원본을 통째로 읽어 실었다"이지 "이 번호까지의 변경을 어떻게든
 * 반영했다"가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotInstaller {

    private final SnapshotLoader loader;
    private final SnapshotBox snapshotBox;
    private final PlaceViewHolder placeViewHolder;
    private final TagViewHolder tagViewHolder;
    private final CacheWriteLock writeLock;

    /**
     * 설치를 기다리는 표시값 패치. 키는 장소 id, 값은 그 패치가 <b>관측한</b> revision과 얹을
     * 표시값({@code null}이면 삭제).
     *
     * <p>{@link CacheWriteLock} 안에서만 읽고 쓴다. 리빌드가 자기보다 새 패치를 다시 얹은 뒤
     * 낡은 것을 버리므로, 평상시 크기는 "직전 리빌드 이후 어드민이 고친 장소 수"다.
     */
    private final Map<Long, PendingPlacePatch> pendingPlacePatches = new HashMap<>();

    /** 태그는 한 벌 통째로 다시 읽는 것이 싸다 — 마지막 패치 하나만 보관한다. */
    private PendingTagPatch pendingTagPatch;

    private record PendingPlacePatch(long revision, PlaceView view) {}

    private record PendingTagPatch(long revision, Map<Long, TagView> tagViews) {}

    /** 이 인스턴스가 지금 싣고 있는 원본 시점. 아직 못 지었으면 {@code -1}. */
    public long installedRevision() {
        Snapshot held = snapshotBox.current();
        return held == null ? SnapshotMetadata.NOT_INSTALLED : held.revision();
    }

    /** 이 인스턴스가 지금 발급하는 커서의 회차. 아직 못 지었으면 {@code -1}. */
    public long installedCursorVersion() {
        Snapshot held = snapshotBox.current();
        return held == null ? SnapshotMetadata.NOT_INSTALLED : held.cursorVersion();
    }

    /**
     * 원본을 한 시점으로 읽어 스냅샷을 짓고 설치한다. 리빌드의 전부다.
     *
     * @return 실제로 설치했으면 {@code true}. 읽어 온 시점이 이미 설치한 것보다 낡았으면
     *         {@code false}(정상 — 겹쳐 돈 리빌드가 늦게 도착한 것이다)
     */
    public boolean rebuildAndInstall() {
        long startNanos = System.nanoTime();
        SnapshotLoader.SourceState source = loader.readSourceState();
        // ★ 정렬은 트랜잭션·락 밖이다. 여기까지가 이 메서드에서 가장 오래 걸리는 구간이고,
        //   그 구간에 조회 경로도 다른 쓰기도 막히지 않는다
        SortedPlaces sorted = SortedPlaces.of(source.entries());
        boolean installed = install(source, sorted);
        if (!installed) {
            log.info("리빌드 결과가 이미 설치한 것보다 낡아 버린다 - read={}, installed={}",
                    source.metadata().revision(), installedRevision());
            return false;
        }
        log.info("목록 스냅샷 설치 - revision={}, cursorVersion={}, places={}, towns={},"
                        + " arrays={}, tags={}, elapsed={}ms",
                source.metadata().revision(), source.metadata().cursorVersion(),
                sorted.placeCount(), sorted.townCount(), sorted.arrayCount(),
                source.tagViews().size(),
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        return true;
    }

    private boolean install(SnapshotLoader.SourceState source, SortedPlaces sorted) {
        SnapshotMetadata read = source.metadata();
        return writeLock.call(() -> {
            Snapshot held = snapshotBox.current();
            if (held != null && read.revision() <= held.revision()) {
                return false;
            }
            // ★ 홀더에 걸기 "전"에 후보 맵 위에서 밀린 패치를 합친다. 걸고 나서 얹으면 조회가
            //   그 사이에 옛 표시값을 본다 — 조회는 이 락을 잡지 않는다
            ConcurrentMap<Long, PlaceView> views = mergePendingPlacePatches(
                    source.views(), read.revision());
            Map<Long, TagView> tagViews = mergePendingTagPatch(
                    source.tagViews(), read.revision());

            // 표시값이 스냅샷보다 먼저다 — 순서가 반대면 새 배열에만 있는 장소가 홀더에 아직
            // 없어 조회 경로가 그 행을 건너뛰는 창이 열린다
            placeViewHolder.replaceAll(views);
            tagViewHolder.replaceAll(tagViews);
            snapshotBox.adopt(new Snapshot(read.revision(), read.cursorVersion(), sorted));
            return true;
        });
    }

    /**
     * 이번에 읽어 온 시점보다 <b>뒤에</b> 관측된 표시값 패치를 후보 맵 위에 얹는다. 그보다 앞선
     * 것은 이번 읽기가 이미 싣고 왔으므로 버린다.
     */
    private ConcurrentMap<Long, PlaceView> mergePendingPlacePatches(
            ConcurrentMap<Long, PlaceView> candidate, long installedFrom) {
        Iterator<Map.Entry<Long, PendingPlacePatch>> it = pendingPlacePatches.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PendingPlacePatch> entry = it.next();
            if (entry.getValue().revision() <= installedFrom) {
                it.remove();
                continue;
            }
            applyPlaceView(candidate, entry.getKey(), entry.getValue().view());
        }
        return candidate;
    }

    private Map<Long, TagView> mergePendingTagPatch(
            Map<Long, TagView> candidate, long installedFrom) {
        if (pendingTagPatch == null) {
            return candidate;
        }
        if (pendingTagPatch.revision() <= installedFrom) {
            pendingTagPatch = null;
            return candidate;
        }
        // 태그 패치는 맵 한 벌을 통째로 다시 읽은 것이라 더 새 시점의 완결된 집합이다
        return pendingTagPatch.tagViews();
    }

    /**
     * 장소 표시값을 지금 홀더에 얹는다. 리빌드를 기다리지 않고 어드민 수정을 보여 주는 경로이며,
     * 부르는 쪽은 <b>그 값을 읽은 트랜잭션이 관측한 revision</b>을 함께 넘긴다.
     *
     * <p>건너뛰는 조건이 둘이다. 이미 그 시점 이후를 통째로 읽어 실었거나, <b>같은 장소에 더 새
     * 패치가 이미 밀려 있거나</b>. 뒤쪽이 없으면 느리게 도착한 옛 패치가 새 패치를 덮는다.
     *
     * @param placeIds 이 수정이 손댄 장소 전부
     * @param views    그중 원본에 남아 있는 것들의 표시값. {@code placeIds}에 있는데 여기 없는
     *                 id는 사라진 장소라 홀더에서 지운다
     */
    void patchPlaceViews(long revision, Collection<Long> placeIds, Map<Long, PlaceView> views) {
        writeLock.run(() -> {
            if (revision <= installedRevision()) {
                return;     // 이미 그 시점 이후를 통째로 읽어 실었다 — 얹을 것이 없다
            }
            for (Long placeId : placeIds) {
                PendingPlacePatch pending = pendingPlacePatches.get(placeId);
                if (pending != null && pending.revision() >= revision) {
                    continue;   // 더 새 패치가 이미 얹혀 있다
                }
                PlaceView view = views.get(placeId);
                if (view == null) {
                    placeViewHolder.remove(placeId);
                } else {
                    placeViewHolder.put(view);
                }
                pendingPlacePatches.put(placeId, new PendingPlacePatch(revision, view));
            }
        });
    }

    /** 태그 표시값 한 벌. 장소 쪽과 같은 규율을 따른다. */
    void patchTagViews(long revision, Map<Long, TagView> tagViews) {
        writeLock.run(() -> {
            if (revision <= installedRevision()) {
                return;
            }
            if (pendingTagPatch != null && pendingTagPatch.revision() >= revision) {
                return;     // 더 새 태그 맵이 이미 얹혀 있다
            }
            tagViewHolder.replaceAll(tagViews);
            pendingTagPatch = new PendingTagPatch(revision, tagViews);
        });
    }

    private static void applyPlaceView(
            Map<Long, PlaceView> target, long placeId, PlaceView view) {
        if (view == null) {
            target.remove(placeId);
            return;
        }
        target.put(placeId, view);
    }

    /** 테스트가 밀린 패치의 수를 보는 자리. 운영 경로에는 이 값을 읽는 곳이 없다. */
    int pendingPlacePatchCount() {
        return writeLock.call(pendingPlacePatches::size);
    }
}
