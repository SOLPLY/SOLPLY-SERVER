package org.sopt.solply_server.domain.place.cache.publication;

/** 재빌드 요청 카운터 한 쌍. */
public record RebuildRequestCounters(long requestedSeq, long processedSeq) {

    public boolean hasPending() {
        return requestedSeq > processedSeq;
    }
}
