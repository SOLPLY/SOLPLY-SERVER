package org.sopt.solply_server.domain.place.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.ProcessedMark;
import org.sopt.solply_server.domain.place.cache.publication.RebuildRequestCounters;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayload;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayloadCodec;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.place.cache.publication.StalePublicationBaseException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 쌓인 재빌드 요청을 한 번의 빌드로 접어 발행한다.
 *
 * <p><b>공유 ShedLock을 건다.</b> 짓는 일이 {@code place_stats} 전량 읽기라 인스턴스 수만큼 돌
 * 이유가 없다. 반대로 설치({@link SnapshotInstaller})에는 걸지 않는다 — 거기서 고치는 것은 자기
 * 힙이다.
 *
 * <p><b>빌드 내내 어떤 행 락도 들지 않는다.</b> 요청 행을 잠근 채 지으면 그 사이 커밋하려는 통계
 * 트랜잭션이 멈춘다. 락은 발행 트랜잭션 안에서만, 그것도 짧게 잡는다.
 *
 * <p><b>기준 발행을 빌드 "전"에 잡는다.</b> 발행 직전에 읽으면 빌드가 도는 동안 어드민이나 다른
 * 발행자가 올린 <b>더 새로운 내용을 낡은 후보가 덮는다.</b> 빌드가 길수록 CAS가 깨질 확률이
 * 오르지만, 그 깨짐이 곧 "그동안 새 내용이 있었다"이므로 버리는 것이 맞다.
 *
 * <p><b>짓기 전에 읽은 요청만 처리 표시한다.</b> 빌드 중에 올라간 요청은 그 값 너머에 있어 다음
 * 회차가 가져간다 — 빌드 중 도착한 요청이 처리됐다고 표시되는 창이 없다.
 *
 * <p>발행에 성공해도 <b>자기 힙을 그 자리에서 갈지 않는다.</b> 자기 설치 폴이 다른 인스턴스와
 * 똑같이 내려받는다. 대가는 최대 한 폴 간격의 자기 지연이고, 얻는 것은 복원 경로가 하나라는 것이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotPublisher {

    static final String PUBLISH_LOCK = "place-list-snapshot-publish";

    private final SnapshotLoader loader;
    private final SnapshotPayloadCodec codec;
    private final SnapshotPublicationRepository publicationRepository;
    private final SnapshotRebuildRequestRepository requestRepository;
    private final SnapshotPublicationService publicationService;

    @Scheduled(fixedDelayString = "${solply.place-list-snapshot.publish-poll-interval-ms:5000}")
    @SchedulerLock(name = PUBLISH_LOCK, lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void publishIfRequested() {
        try {
            publishRound();
        } catch (Exception e) {
            log.error("목록 스냅샷 발행 실패 - 직전 발행물과 밀린 요청을 그대로 둔다"
                    + "(다음 회차가 다시 짓는다)", e);
        }
    }

    /** @return 발행했으면 true */
    public boolean publishRound() {
        RebuildRequestCounters counters = requestRepository.readCounters();
        if (!counters.hasPending()) {
            return false;   // 요청이 없으면 포인터도 읽지 않는다
        }
        long observed = counters.requestedSeq();    // ← 코얼레싱 지점
        long startNanos = System.nanoTime();

        // ★ 기준은 빌드 "전"에 잡는다 (클래스 javadoc)
        Long base = publicationRepository.readCurrentPublicationId();
        PublicationCandidate candidate = buildFromSource();

        long publicationId;
        try {
            publicationId = publicationService.publish(candidate, base, ProcessedMark.upTo(observed));
        } catch (StalePublicationBaseException e) {
            log.warn("목록 스냅샷 발행을 놓쳤다 - 빌드 중 다른 발행이 있었다(base={})."
                    + " 요청을 닫지 않는다 - 다음 회차가 다시 짓는다", base);
            return false;
        }
        publicationService.cleanUpQuietly(publicationId);

        log.info("목록 스냅샷 발행 완료 - publication={}, entries={}, bytes={}, requested={},"
                        + " elapsed={}ms",
                publicationId, candidate.entryCount(), candidate.payloadBytes(), observed,
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        return true;
    }

    /**
     * 원본에서 읽어 payload까지 만든다.
     *
     * <p><b>언제나 구조 발행이다</b>({@code carriedCursorVersion == null}) — 발행 트랜잭션이
     * INSERT 뒤 회차를 자기 id로 채운다. 그래서 <b>통계 회차마다 진행 중이던 커서가 만료된다.</b>
     * 옛 10분 무조건 재빌드도 같은 일을 했고 통계 회차는 시간 단위라 빈도가 더 낮지만, 줄어들었다는
     * 주장은 하지 않는다 — 세어 본 적이 없다. 없애려면 새로 지은 엔트리 집합이 현재 발행물과 같은지
     * 비교해 같으면 회차를 이어받아야 하는데, 그 비교 비용과 이득을 재지 않았으므로 넣지 않았다.
     */
    PublicationCandidate buildFromSource() {
        SnapshotLoader.SourceState source = loader.readSourceState();
        return encode(null, source.entries(), source.views(), source.tagViews());
    }

    /**
     * 지금 힙 상태를 그대로 발행 후보로 만든다 — 어드민 훅이 쓴다.
     *
     * @param carriedCursorVersion 표시값만 바뀐 발행이면 이어받을 회차, 구조가 바뀐 발행이면
     *                             {@code null}
     */
    PublicationCandidate encode(Long carriedCursorVersion, List<PlaceEntry> entries,
            Map<Long, PlaceView> views, Map<Long, TagView> tagViews) {
        SnapshotPayload payload = new SnapshotPayload(
                SnapshotPayloadCodec.FORMAT_VERSION,
                toEntryRows(entries), toPlaceRows(views), toTagRows(tagViews));
        byte[] encoded = codec.encode(payload);
        return new PublicationCandidate(carriedCursorVersion, SnapshotPayloadCodec.FORMAT_VERSION,
                entries.size(), codec.checksum(encoded), encoded);
    }

    private static List<SnapshotPayload.Entry> toEntryRows(List<PlaceEntry> entries) {
        List<SnapshotPayload.Entry> rows = new ArrayList<>(entries.size());
        for (PlaceEntry entry : entries) {
            rows.add(new SnapshotPayload.Entry(
                    entry.placeId(), entry.townId(), entry.tagBitmask(), entry.popularScore(),
                    entry.createdAtEpochSecond(), entry.bookmarkCount(), entry.reviewCount(),
                    entry.ratingToInt(), entry.latitude(), entry.longitude()));
        }
        return rows;
    }

    private static List<SnapshotPayload.Place> toPlaceRows(Map<Long, PlaceView> views) {
        List<SnapshotPayload.Place> rows = new ArrayList<>(views.size());
        for (PlaceView view : views.values()) {
            rows.add(new SnapshotPayload.Place(
                    view.placeId(), view.name(), view.thumbnailFileKey(), view.mainTagId()));
        }
        return rows;
    }

    private static List<SnapshotPayload.Tag> toTagRows(Map<Long, TagView> tagViews) {
        List<SnapshotPayload.Tag> rows = new ArrayList<>(tagViews.size());
        for (TagView tag : tagViews.values()) {
            rows.add(new SnapshotPayload.Tag(tag.tagId(), tag.name(), tag.active()));
        }
        return rows;
    }
}
