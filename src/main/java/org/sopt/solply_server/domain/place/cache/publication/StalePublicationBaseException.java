package org.sopt.solply_server.domain.place.cache.publication;

/**
 * 내가 기준으로 잡은 발행이 더 이상 현재가 아니다 — 짓는 동안 누가 먼저 발행했다는 뜻이다.
 *
 * <p><b>{@code RuntimeException}인 것이 계약이다.</b> 발행 트랜잭션이 통째로 롤백돼 방금 넣은
 * payload 행도 없던 일이 되고, 처리 표시도 오르지 않는다. 부른 쪽은 이 예외를 잡아 후보를 버리고
 * <b>요청을 열어 둔 채</b> 다음 회차에 넘긴다.
 */
public class StalePublicationBaseException extends RuntimeException {

    public StalePublicationBaseException(Long basePublicationId, long candidateId) {
        super("기준 발행이 더 이상 현재가 아니다 - base=" + basePublicationId
                + ", 버리는 후보=" + candidateId);
    }
}
