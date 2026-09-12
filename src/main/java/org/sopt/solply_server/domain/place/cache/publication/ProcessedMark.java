package org.sopt.solply_server.domain.place.cache.publication;

/**
 * 발행에 성공한 트랜잭션이 요청을 어디까지 닫는가. 두 가지뿐이다.
 *
 * <p>발행자는 <b>짓기 전에 읽은 값까지</b> 닫고(그 뒤에 들어온 요청은 다음 회차 몫이다), 어드민
 * 훅은 <b>자기 것 하나만</b>, 그것도 앞뒤로 아무도 없을 때만 닫는다.
 */
@FunctionalInterface
public interface ProcessedMark {

    void apply(SnapshotRebuildRequestRepository repository);

    /** 발행자용 — 관측한 순번까지 닫는다. */
    static ProcessedMark upTo(long observedSeq) {
        return repository -> repository.markProcessed(observedSeq);
    }

    /** 어드민 훅용 — 앞에 밀린 것도 뒤에 끼어든 것도 없을 때만 자기 것을 닫는다. */
    static ProcessedMark solelyMine(long mySeq) {
        return repository -> repository.markProcessedIfSolelyMine(mySeq);
    }

    /** 최초 부트스트랩 — 닫을 요청이 없다. */
    static ProcessedMark none() {
        return repository -> { };
    }
}
