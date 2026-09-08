package org.sopt.solply_server.domain.place.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 장소 → {@link PlaceView}. 회차 사진 <b>바깥</b>에 사는 표시값 저장소다.
 *
 * <p><b>계약 — 커서가 보장하는 것은 순서뿐이고 표시값은 최신일 수 있다.</b> 사진은 회차마다
 * 통째로 바뀌지만 이 맵은 어드민 수정 한 건마다 그 항목만 갈린다. 그래서 옛 회차의 사진을 보는
 * 스크롤도 이름·썸네일·대표 태그는 지금 값을 본다 — 이름 하나 고치자고 전량을 다시 짓지 않기
 * 위해 받아들인 계약이다.
 *
 * <p><b>조회는 락을 잡지 않는다.</b> {@link #get}은 {@code volatile} 참조 한 번과
 * {@code ConcurrentHashMap} 읽기 한 번이 전부다. 반대로 쓰기({@link #replaceAll}·{@link #put})는
 * {@link PlaceListWriteLock} 안에서만 불러야 한다 — 그 이유는 그쪽 javadoc.
 *
 * <p><b>{@code get}이 {@code null}일 수 있다.</b> 옛 사진에만 남아 있고 그 사이 삭제된 장소가
 * 그렇다. 조회 경로는 그 행을 건너뛴다({@code PlaceService#listPlaces}).
 */
@Component
public class PlaceViewHolder {

    /** 재빌드는 이 참조를 통째로 갈고, 패치는 가리키는 맵의 한 항목만 고친다 */
    private volatile Map<Long, PlaceView> views = new ConcurrentHashMap<>();

    /** 없으면 {@code null} — 호출자가 "그 사이 사라진 장소"로 번역한다 */
    public PlaceView get(long placeId) {
        return views.get(placeId);
    }

    /**
     * 전량 재빌드가 만든 새 맵으로 참조를 교체한다. 옛 맵을 고치지 않으므로 지금 그 맵을 읽고
     * 있는 요청은 자기가 잡은 맵을 끝까지 본다.
     */
    void replaceAll(Map<Long, PlaceView> fresh) {
        this.views = new ConcurrentHashMap<>(fresh);
    }

    /** 어드민 수정 한 건 — 그 장소만 갈아 끼운다 */
    void put(PlaceView view) {
        views.put(view.placeId(), view);
    }
}
