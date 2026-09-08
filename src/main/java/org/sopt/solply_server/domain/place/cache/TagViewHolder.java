package org.sopt.solply_server.domain.place.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 태그 id → {@link TagView}. {@link PlaceViewHolder}와 같은 계약·같은 동기화 규칙을 따르며,
 * 다른 것은 크기뿐이다 — 태그는 수십 행이라 재빌드가 전량을 한 문장으로 읽는다.
 *
 * <p>대표 태그 이름은 여기서 완성된다: {@link PlaceView#mainTagId()}가 가리키는 항목이 없거나
 * 비활성이면 이름은 {@code null}이다({@code TagViewUtils.getActiveNameOrNull}과 같은 규칙).
 * 그래서 태그 하나를 비활성으로 내리는 것은 이 맵의 한 항목을 갈아 끼우는 일이며, 그 태그를 단
 * 장소들을 찾아다닐 필요가 없다.
 */
@Component
public class TagViewHolder {

    private volatile Map<Long, TagView> views = new ConcurrentHashMap<>();

    /** 없으면 {@code null} — 호출자는 이름 없음(= 대표 태그 미표시)으로 번역한다 */
    public TagView get(long tagId) {
        return views.get(tagId);
    }

    void replaceAll(Map<Long, TagView> fresh) {
        this.views = new ConcurrentHashMap<>(fresh);
    }

    void put(TagView view) {
        views.put(view.tagId(), view);
    }
}
