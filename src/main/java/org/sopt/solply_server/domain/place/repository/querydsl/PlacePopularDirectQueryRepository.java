package org.sopt.solply_server.domain.place.repository.querydsl;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * [벤치마크 v0 전용] 인기순 조회를 캐시 없이 DB에서 직접 수행한다.
 * solply.place-list.popular-read-mode=db 일 때만 사용된다.
 * 장소별 카운트는 상관 서브쿼리 — idx_bookmark_target 커버링으로 장소당 인덱스 range scan 1회.
 * 태그 필터 의미론은 CachedPlaceFilter와 동일 (타입 내 OR, 타입 간 AND, 메인 없으면 서브 무시).
 */
@Repository
@RequiredArgsConstructor
public class PlacePopularDirectQueryRepository {

    private final EntityManager em;

    public record PopularRow(long placeId, long bookmarkCount) {}

    @SuppressWarnings("unchecked")
    public List<PopularRow> findPopularRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Long cursorCount, Long cursorPlaceId, int limit) {

        boolean useMainTag = mainTagId != null;
        boolean useSubA = useMainTag && subTagAIds != null && !subTagAIds.isEmpty();
        boolean useSubB = useMainTag && subTagBIds != null && !subTagBIds.isEmpty();
        boolean useCursor = cursorCount != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT x.id, x.cnt FROM (
                    SELECT p.id AS id,
                           (SELECT COUNT(*) FROM bookmarks b
                             WHERE b.target_type = 'PLACE' AND b.target_id = p.id) AS cnt
                    FROM places p
                    WHERE p.town_id IN (:townIds)
                      AND p.active = 1
                """);
        if (useMainTag) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
                                   WHERE pt.place_id = p.id AND t.id = :mainTagId AND t.active = 1)
                    """);
        }
        if (useSubA) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
                                   WHERE pt.place_id = p.id AND t.id IN (:subTagAIds) AND t.active = 1)
                    """);
        }
        if (useSubB) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
                                   WHERE pt.place_id = p.id AND t.id IN (:subTagBIds) AND t.active = 1)
                    """);
        }
        sql.append(") x ");
        if (useCursor) {
            sql.append("WHERE x.cnt < :cursorCount OR (x.cnt = :cursorCount AND x.id > :cursorPlaceId) ");
        }
        sql.append("ORDER BY x.cnt DESC, x.id ASC LIMIT :limitSize");

        var query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", townIds)
                .setParameter("limitSize", limit);
        if (useMainTag) query.setParameter("mainTagId", mainTagId);
        if (useSubA) query.setParameter("subTagAIds", subTagAIds);
        if (useSubB) query.setParameter("subTagBIds", subTagBIds);
        if (useCursor) {
            query.setParameter("cursorCount", cursorCount);
            query.setParameter("cursorPlaceId", cursorPlaceId);
        }

        List<Object[]> rows = query.getResultList();
        List<PopularRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new PopularRow(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        }
        return result;
    }
}
