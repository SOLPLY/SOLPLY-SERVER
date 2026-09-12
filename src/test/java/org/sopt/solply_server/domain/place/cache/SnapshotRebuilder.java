package org.sopt.solply_server.domain.place.cache;

import org.sopt.solply_server.domain.place.cache.publication.ProcessedMark;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;

/**
 * 테스트가 <b>"지금 원본에서 다시 지어 설치하라"</b>고 말하는 손잡이.
 *
 * <p>옛 {@code SnapshotLoader#rebuild()} 한 줄이 하던 일이 2026-09-12에 셋으로 갈렸다 —
 * 원본 읽기(발행자) · 발행(발행 서비스) · 내려받아 설치(설치자). IT마다 그 셋을 손으로 엮으면
 * 순서를 잘못 적은 곳이 조용히 생기므로 한자리에 모은다.
 *
 * <p><b>요청 카운터를 거치지 않는다.</b> 발행자의 정상 경로({@code publishRound})는 밀린 요청이
 * 있을 때만 돌지만, 여기서는 테스트가 "지금 지어라"라고 직접 말하는 것이라 요청을 세지 않는다
 * ({@link ProcessedMark#none()}). 요청 카운터의 코얼레싱·처리 표시 자체를 보는 검증은 발행자를
 * 직접 부르는 IT의 몫이다.
 *
 * <p>이 클래스가 {@code cache} 패키지에 있는 이유는 {@code buildFromSource}가 패키지 전용이기
 * 때문이다. 다른 패키지의 IT도 public 메서드로 쓸 수 있다.
 */
public final class SnapshotRebuilder {

    private final SnapshotPublisher publisher;
    private final SnapshotPublicationRepository publicationRepository;
    private final SnapshotPublicationService publicationService;
    private final SnapshotInstaller installer;

    public SnapshotRebuilder(SnapshotPublisher publisher,
            SnapshotPublicationRepository publicationRepository,
            SnapshotPublicationService publicationService,
            SnapshotInstaller installer) {
        this.publisher = publisher;
        this.publicationRepository = publicationRepository;
        this.publicationService = publicationService;
        this.installer = installer;
    }

    /**
     * 원본에서 전량을 지어 발행하고, 그것을 내려받아 설치한다.
     *
     * @return 이번에 발행된 발행물 id
     */
    public long rebuildAndInstall() {
        long publicationId = publish();
        installer.installLatest();
        return publicationId;
    }

    /** 발행만 한다 — 설치하지 않은 채로 두는 상황(다른 노드가 먼저 받는 경우)을 만들 때 쓴다 */
    public long publish() {
        Long base = publicationRepository.readCurrentPublicationId();
        return publicationService.publish(publisher.buildFromSource(), base, ProcessedMark.none());
    }

    /**
     * <b>표시값만 바뀐 발행</b> — 발행 id는 오르지만 커서 회차는 넘겨받은 값을 그대로 이어받는다.
     * 운영에서 이 모양을 만드는 것은 어드민 수정이고({@code SnapshotRefresher}), 진행 중인 커서를
     * 끊지 않는 것이 그 발행의 요점이다.
     *
     * <p>설치하지 않는다 — "발행 id는 앞섰는데 회차는 그대로인" 상태를 다른 인스턴스에서 만들어
     * 두려는 용도라, 설치까지 하면 그 상태가 사라진다.
     *
     * @param carriedCursorVersion 이어받을 커서 회차. 보통 직전 발행물의 {@code cursor_version}이다
     * @return 새로 정해진 발행 id
     */
    public long publishCarrying(long carriedCursorVersion) {
        PublicationCandidate built = publisher.buildFromSource();
        PublicationCandidate carried = new PublicationCandidate(carriedCursorVersion,
                built.formatVersion(), built.entryCount(), built.payloadSha256(), built.payload());
        return publicationService.publish(
                carried, publicationRepository.readCurrentPublicationId(), ProcessedMark.none());
    }
}
