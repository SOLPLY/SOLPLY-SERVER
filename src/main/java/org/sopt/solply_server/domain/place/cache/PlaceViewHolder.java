package org.sopt.solply_server.domain.place.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 장소 → {@link PlaceView}. 회차 스냅샷 <b>바깥</b>에 사는 표시값 저장소다.
 *
 * <p><b>계약 — 커서가 보장하는 것은 순서뿐이고 표시값은 최신일 수 있다.</b> 스냅샷은 회차마다
 * 통째로 바뀌지만 이 맵은 어드민 수정 한 건마다 그 항목만 갈린다. 그래서 옛 회차의 스냅샷을 보는
 * 스크롤도 이름·썸네일·대표 태그는 지금 값을 본다 — 이름 하나 고치자고 전량을 다시 짓지 않기
 * 위해 받아들인 계약이다.
 *
 * <p><b>조회는 락을 잡지 않는다.</b> {@link #get}은 {@code volatile} 참조 한 번과
 * {@code ConcurrentHashMap} 읽기 한 번이 전부다. 반대로 쓰기({@link #replaceAll}·{@link #put})는
 * {@link CacheWriteLock} 안에서만 불러야 한다 — 그 이유는 그쪽 javadoc.
 *
 * <p><b>{@code get}이 {@code null}일 수 있다.</b> 옛 스냅샷에만 남아 있고 그 사이 삭제된 장소가
 * 그렇다. 조회 경로는 그 행을 건너뛴다({@code PlaceService#listPlaces}).
 */
@Component
public class PlaceViewHolder {

    /** 재빌드는 이 참조를 통째로 갈고, 패치는 가리키는 맵의 한 항목만 고친다 */
    private volatile ConcurrentMap<Long, PlaceView> views = new ConcurrentHashMap<>();

    /** 없으면 {@code null} — 호출자가 "그 사이 사라진 장소"로 번역한다 */
    public PlaceView get(long placeId) {
        return views.get(placeId);
    }

    /**
     * 전량 재빌드가 만든 새 맵으로 참조를 교체한다. 옛 맵을 고치지 않으므로 교체가 <b>진행 중인
     * 맵을 반쯤 지운 상태</b>로 보이는 일은 없다.
     *
     * <p><b>다만 요청 하나가 한 맵만 보는 것은 아니다.</b> 조회 경로는 응답에 실을 행마다
     * {@link #get}을 부르므로, 한 요청 안에서도 교체 앞뒤의 값이 섞일 수 있다 — 10건짜리 페이지의
     * 앞 3건은 옛 이름, 뒤 7건은 새 이름. <b>그것이 계약이다</b>: 커서가 보장하는 것은 정렬 순서의
     * 일관성까지이고 표시값은 최신일 수 있다. 요청 하나가 한 회차로 고정되는 것은 스냅샷
     * ({@code SnapshotBox} 계약 2)이지 이 맵이 아니다.
     *
     * <p><b>받은 맵을 복사하지 않는다 — 소유권이 넘어온다.</b> 넘긴 쪽은 그 뒤로 이 맵을 건드리지
     * 않아야 한다. 복사본을 하나 더 뜨면 전 장소의 표시값을 재빌드마다 두 번 담게 되는데, 그것을
     * 아끼려고 만드는 쪽이 처음부터 홀더가 쓸 맵을 만들어 준다({@code SnapshotLoader#readSource}).
     *
     * <p>파라미터가 {@link ConcurrentMap}인 것도 그래서다. 패치({@link #put})가 이 맵의 항목을
     * 직접 고치므로 일반 {@code HashMap}이 들어오면 조회와 수정이 겹치는 순간 깨진다 — 타입으로
     * 막아 둔다.
     */
    void replaceAll(ConcurrentMap<Long, PlaceView> fresh) {
        this.views = fresh;
    }

    /** 어드민 수정 한 건 — 그 장소만 갈아 끼운다 */
    void put(PlaceView view) {
        views.put(view.placeId(), view);
    }
}
