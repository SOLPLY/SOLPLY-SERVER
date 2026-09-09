package org.sopt.solply_server.domain.place.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 태그 id → {@link TagView}. {@link PlaceViewHolder}와 같은 계약·같은 동기화 규칙을 따르되,
 * <b>항목 단위로 고치는 문이 없다</b> — 태그는 수십 행이라 재빌드도 어드민 훅도 전량을 한 문장으로
 * 읽어 통째로 교체한다({@link SnapshotRefresher#refreshTagViewsAfterCommit}). 그래서 삭제된
 * 태그가 맵에 남는 경로도 없다.
 *
 * <p>대표 태그 이름은 여기서 완성된다: {@link PlaceView#mainTagId()}가 가리키는 항목이 없거나
 * 비활성이면 이름은 {@code null}이다({@code TagViewUtils.getActiveNameOrNull}과 같은 규칙).
 * 그래서 태그 하나를 비활성으로 내려도 그 태그를 단 장소들을 찾아다닐 필요가 없다.
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
}
