package org.sopt.solply_server.domain.place.metrics;

import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

/**
 * 예산 안에 끝내지 못해 <b>재시도 가능 오류로 끊은</b> 요청. 사용자에게 보이는 것은 평범한
 * {@link ErrorCode#PLACE_SNAPSHOT_SYNCING}이고, 달라지는 것은 <b>왜 끊었는지를 함께 싣는다</b>는
 * 것뿐이다.
 *
 * <p><b>왜 예외에 이유를 싣는가.</b> 계측의 단위가 "요청 하나"라, 세는 자리는 요청의 확정 CAS에
 * 이긴 <b>한 곳</b>이어야 한다({@code PlaceListRequestOrchestrator#settleExceptionally}). 그런데
 * 이유를 아는 곳은 훨씬 안쪽이다 — 시계·적재 대기·재관측 한도가 서로 다른 층에 있다. 이유를
 * 예외에 실어 올리면 <b>아는 곳에서 정하고 세는 곳에서 한 번만 센다</b>가 성립한다.
 *
 * <p>커서 만료는 이 예외가 아니다. 그것은 예산을 넘긴 것이 아니라 계약대로 답한 것이다.
 */
public class PlaceListBudgetException extends BusinessException {

    private final transient PlaceListMeters.BudgetReason reason;

    public PlaceListBudgetException(PlaceListMeters.BudgetReason reason) {
        super(ErrorCode.PLACE_SNAPSHOT_SYNCING);
        this.reason = reason;
    }

    public PlaceListMeters.BudgetReason reason() {
        return reason;
    }
}
