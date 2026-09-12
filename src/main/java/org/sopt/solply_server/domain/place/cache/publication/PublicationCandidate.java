package org.sopt.solply_server.domain.place.cache.publication;

/**
 * 발행 후보 — 인코딩까지 끝나 INSERT에 그대로 실릴 준비가 된 상태다.
 *
 * @param carriedCursorVersion {@code null}이면 <b>구조 발행</b>이고, 발행 트랜잭션이 INSERT 뒤
 *                             {@code cursor_version = id}로 채운다. 값이 있으면 <b>표시값만
 *                             바뀐 발행</b>이라 직전 회차를 그대로 이어받는다 — 진행 중인 커서를
 *                             끊지 않는 자리다
 */
public record PublicationCandidate(
        Long carriedCursorVersion,
        int formatVersion,
        int entryCount,
        String payloadSha256,
        byte[] payload) {

    public int payloadBytes() {
        return payload.length;
    }

    public boolean isStructural() {
        return carriedCursorVersion == null;
    }
}
