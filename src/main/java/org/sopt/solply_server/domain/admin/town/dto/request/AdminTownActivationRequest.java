package org.sopt.solply_server.domain.admin.town.dto.request;

import jakarta.validation.constraints.NotNull;

public record AdminTownActivationRequest(
	@NotNull(message = "업데이트할 상태는 필수입니다.")
	Boolean active,

	/**
	 * 동네를 되살릴 때 <b>사용자의 목록을 새로 시작시킬지</b>. 생략하면 {@code false}.
	 *
	 * <p>동네 하나를 켜면 그 동네 장소 수백 건이 한꺼번에 목록에 들어오므로, 스크롤 중이던
	 * 사용자가 보는 순서는 실제로 크게 갈린다. 그래도 <b>자동으로 올리지는 않는다</b> —
	 * 되살리기가 트래픽 시간대에 일어날지 새벽에 일어날지는 여기서 알 수 없고, 그 판단은
	 * 작업하는 어드민이 한다.
	 */
	Boolean restartPlaceList
) {

	/** 생략을 "유지"로 읽는다. */
	public boolean restartsPlaceList() {
		return Boolean.TRUE.equals(restartPlaceList);
	}
}
