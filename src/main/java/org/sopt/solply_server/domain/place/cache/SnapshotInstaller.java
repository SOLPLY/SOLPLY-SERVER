package org.sopt.solply_server.domain.place.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.cache.publication.PublishedSnapshot;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayload;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayloadCodec;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.springframework.stereotype.Component;

/**
 * 발행물을 내려받아 이 인스턴스의 힙에 설치하는 <b>유일한</b> 곳.
 *
 * <p><b>ShedLock을 걸지 않는다.</b> 고치는 것이 공유 DB가 아니라 자기 힙이라 인스턴스마다 각자
 * 해야 한다. 발행자 노릇을 하는 인스턴스도 자기가 방금 발행한 것을 이 경로로 내려받는다 —
 * 설치 경로를 하나로 두는 것이 "모든 인스턴스가 같은 배열을 짓는다"의 근거다.
 *
 * <p><b>후보를 다 지은 뒤에 갈아 끼운다.</b> 체크섬·형식·디코딩·정렬 어디서 실패하든 홀더는
 * 손대지 않았으므로 지금 스냅샷이 그대로 서빙되고, 설치 id도 그대로라 다음 폴이 다시 시도한다.
 *
 * <p><b>회차는 발행물이 들고 온 값을 그대로 쓴다.</b> 번호를 새로 받지 않으므로 같은 발행물을
 * 복원한 인스턴스들이 같은 회차를 든다 — 배포가 커서를 끊지 않는 근거가 이 한 줄이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotInstaller {

    private final SnapshotPublicationRepository publicationRepository;
    private final SnapshotPayloadCodec codec;
    private final SnapshotBox snapshotBox;
    private final PlaceViewHolder placeViewHolder;
    private final TagViewHolder tagViewHolder;
    private final CacheWriteLock writeLock;

    /**
     * 마지막으로 설치한 발행 id. <b>쓰기는 {@link CacheWriteLock} 안에서만 한다</b> — 이 값이
     * 세 홀더의 순서를 정하는 유일한 값이라, 락 밖에서 고치면 늦게 도착한 옛 payload가 새
     * 표시값을 덮는 창이 열린다. <b>{@code volatile}은 락을 잡지 않는 관측자를 위한 것이다</b>
     * ({@link #observedPublicationId()}).
     */
    private volatile long installedPublicationId = -1L;

    /** 아직 한 번도 설치하지 못했으면 {@code -1}. */
    public long installedPublicationId() {
        return writeLock.call(() -> installedPublicationId);
    }

    /**
     * 같은 값을 <b>락을 잡지 않고</b> 본다. 조회 경로가 "내가 뒤처졌나"를 물을 때 쓰는 자리다.
     *
     * <p><b>락을 잡지 않는 것이 요점이다.</b> 어드민 훅은 이 락을 자기 전 구간 동안 쥐므로
     * ({@link SnapshotRefresher}), 목록 요청마다 락을 잡으면 어드민 수정 한 번이 그동안의 모든
     * 첫 페이지를 그 락 앞에 줄 세운다. 조회는 캐시 쓰기를 기다리지 않는다는 것이
     * {@link CacheWriteLock}의 계약이기도 하다.
     *
     * <p><b>돌려주는 것은 판정이 아니라 힌트다.</b> 읽는 순간 설치가 진행 중이면 직전 값을 볼 수
     * 있다. 그래도 안전한 것은 이 값이 <b>단조 증가</b>라 틀리는 방향이 하나뿐이기 때문이다 —
     * 실제보다 낮게 보여 한 번 더 기다릴 수는 있어도, 뒤처진 상태를 따라잡았다고 보지는 않는다.
     */
    public long observedPublicationId() {
        return installedPublicationId;
    }

    /** @return 설치했으면 true. 포인터가 그대로라 할 일이 없었으면 false */
    public boolean installIfChanged() {
        Long current = publicationRepository.readCurrentPublicationId();
        if (current == null) {
            return false;   // 아직 아무도 발행하지 않았다 — 기동 부트스트랩이 맡는 자리다
        }
        if (current == installedPublicationId()) {
            return false;   // payload를 읽지 않는다
        }
        return installLatest();
    }

    /** 포인터를 보지 않고 지금 발행물을 내려받아 설치한다. 기동과 어드민 훅이 쓴다. */
    public boolean installLatest() {
        PublishedSnapshot published = publicationRepository.download().orElse(null);
        if (published == null) {
            return false;
        }
        codec.requireIntact(published.payloadSha256(), published.payload());
        SnapshotPayload payload = codec.decode(
                published.formatVersion(), published.payload(), published.entryCount());

        SortedPlaces sorted = SortedPlaces.of(toEntries(payload.entries()));
        ConcurrentMap<Long, PlaceView> views = toViews(payload.places());
        Map<Long, TagView> tagViews = toTagViews(payload.tags());

        boolean installed = writeLock.call(() -> {
            // ★ 내려받기·디코딩은 락 밖에서 돌았다. 그 사이 어드민 훅이 더 새 발행물을 설치했을
            //   수 있고, 그러면 늦게 도착한 이 payload가 새 표시값·태그를 덮는다. 락 안에서 다시
            //   보는 것이 그 창을 닫는 유일한 자리다
            if (published.publicationId() <= installedPublicationId) {
                return false;
            }
            // 표시값이 스냅샷보다 먼저다 — 순서가 반대면 새 배열에만 있는 장소가 홀더에 아직
            // 없어 조회 경로가 그 행을 건너뛰는 창이 열린다
            placeViewHolder.replaceAll(views);
            tagViewHolder.replaceAll(tagViews);
            // 회차가 같으면 SnapshotBox의 단조 가드가 참조를 바꾸지 않는다 — 표시값만 바뀐
            // 발행이 진행 중인 커서를 끊지 않는 자리다
            snapshotBox.adopt(new Snapshot(published.cursorVersion(), sorted));
            installedPublicationId = published.publicationId();
            return true;
        });

        if (!installed) {
            log.info("내려받은 발행물이 이미 설치한 것보다 낡아 버린다 - publication={}",
                    published.publicationId());
            return false;
        }
        log.info("목록 스냅샷 설치 - publication={}, cursorVersion={}, places={}, towns={},"
                        + " arrays={}, tags={}, bytes={}",
                published.publicationId(), published.cursorVersion(), sorted.placeCount(),
                sorted.townCount(), sorted.arrayCount(), tagViews.size(),
                published.payloadBytes());
        return true;
    }

    private static List<PlaceEntry> toEntries(List<SnapshotPayload.Entry> rows) {
        List<PlaceEntry> entries = new ArrayList<>(rows.size());
        for (SnapshotPayload.Entry row : rows) {
            entries.add(new PlaceEntry(
                    row.placeId(), row.townId(), row.tagBitmask(), row.popularScore(),
                    row.createdAtEpochSecond(), row.bookmarkCount(), row.reviewCount(),
                    row.ratingToInt(), row.latitude(), row.longitude()));
        }
        return entries;
    }

    private static ConcurrentMap<Long, PlaceView> toViews(List<SnapshotPayload.Place> rows) {
        // 홀더가 그대로 받아 쓰는 맵이라 여기서 처음부터 동시 수정 가능한 것으로 만든다
        ConcurrentMap<Long, PlaceView> views = new ConcurrentHashMap<>(rows.size() * 2);
        for (SnapshotPayload.Place row : rows) {
            views.put(row.placeId(), new PlaceView(
                    row.placeId(), row.name(), row.thumbnailFileKey(), row.mainTagId()));
        }
        return views;
    }

    private static Map<Long, TagView> toTagViews(List<SnapshotPayload.Tag> rows) {
        Map<Long, TagView> tagViews = new HashMap<>(rows.size() * 2);
        for (SnapshotPayload.Tag row : rows) {
            tagViews.put(row.tagId(), new TagView(row.tagId(), row.name(), row.active()));
        }
        return tagViews;
    }
}
