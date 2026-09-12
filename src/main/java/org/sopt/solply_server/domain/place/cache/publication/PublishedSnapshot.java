package org.sopt.solply_server.domain.place.cache.publication;

/**
 * 내려받은 발행물 한 벌 — 포인터가 가리키는 행을 <b>한 문장</b>으로 조인해 읽은 결과다.
 *
 * <p>회차는 이 record가 들고 온 {@code cursorVersion}을 쓰지, 폴에서 본 값을 쓰지 않는다.
 */
public record PublishedSnapshot(
        long publicationId,
        long cursorVersion,
        int formatVersion,
        int entryCount,
        int payloadBytes,
        String payloadSha256,
        byte[] payload) {
}
