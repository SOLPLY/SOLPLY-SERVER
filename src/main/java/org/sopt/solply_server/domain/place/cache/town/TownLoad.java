package org.sopt.solply_server.domain.place.cache.town;

/**
 * 공유 확보 한 번의 결과. {@code places}는 <b>요청한 번호 그대로</b>이거나 {@code null}이다 —
 * 다른 번호의 객체를 여기 싣지 않는다.
 *
 * @param places 요청한 번호의 객체. 확보하지 못했으면 {@code null}
 * @param shared 로컬 미스 뒤 공유 사본을 본 결과. 로컬 hit이면 {@link Shared#NOT_ASKED}
 */
public record TownLoad(TownPlaces places, Shared shared) {

    public enum Shared {
        NOT_ASKED,
        HIT,
        MISS,
        /** 접속 실패·시간 초과·깨진 내용. 이 번호가 없다는 것을 확인하지 못했다. */
        UNAVAILABLE
    }

    public static TownLoad local(TownPlaces places) {
        return new TownLoad(places, Shared.NOT_ASKED);
    }

    public boolean found() {
        return places != null;
    }
}
