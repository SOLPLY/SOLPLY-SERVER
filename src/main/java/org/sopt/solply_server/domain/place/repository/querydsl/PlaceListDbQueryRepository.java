package org.sopt.solply_server.domain.place.repository.querydsl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * [db 모드] 장소 목록을 캐시 없이 DB에서 정렬·필터·페이징한다.
 * {@code solply.place-list.popular-read-mode=db} 일 때만 쓰인다 — 캐시(A) vs DB 직행(B) A/B 벤치의 B다.
 *
 * <p><b>벤치 v0(요청마다 bookmarks를 COUNT(*)로 세던 구현)를 대체한다.</b> v0는 캐시 경로와 랭킹
 * 소스가 달라(원시 북마크 수 vs 복합 점수) 두 모드의 응답을 diff로 대조할 수 없었고, 그래서
 * "지연은 비교되지만 정합은 비교되지 않는" 반쪽짜리 B였다. 지금은 양쪽 모두 place_stats의
 * popular_score를 랭킹 소스로 쓰므로 <b>두 모드의 커서가 호환되고 응답이 동일해야 한다</b> —
 * 그 동일성 자체가 벤치의 검증 장치다.
 *
 * <p><b>커서 계약.</b> {@code PlaceListCursor} v2를 그대로 쓴다. sortKey는 캐시 경로
 * {@code PlaceListPaginator.sortKeyOf}와 같은 값이다 — POPULAR은 popular_score의 double,
 * LATEST는 createdAt의 epoch 초(UTC). 정렬 규칙도 캐시 경로의 {@code comparatorOf}와 같다:
 * POPULAR은 (점수 DESC, id ASC), LATEST는 (생성일 DESC, id DESC). 한쪽만 바꾸면 모드 간
 * diff가 깨지므로 둘은 함께 움직여야 한다.
 *
 * <p>태그 필터 의미론은 {@code CachedPlaceFilter}와 동일하다 (타입 내 OR, 타입 간 AND,
 * 메인 태그가 없으면 서브 태그는 무시).
 */
@Repository
@RequiredArgsConstructor
public class PlaceListDbQueryRepository {

    private final EntityManager em;

    public record PopularRow(long placeId, double popularScore, long bookmarkCount) {}

    public record LatestRow(long placeId, LocalDateTime createdAt, long bookmarkCount) {}

    /**
     * 인기순을 place_stats 정렬로 서빙한다 — {@code idx_place_stats_town_score}
     * (town_id, active, popular_score DESC, place_id)가 필터·정렬·타이브레이크를 흡수한다.
     *
     * <p><b>단 "정렬을 흡수한다"가 성립하는 것은 동네(leaf) 단위 조회뿐이다.</b> 술어가
     * {@code town_id IN (:townIds)}이므로 시 단위 조회(leaf 최대 18개)에서는 town별 range가
     * <em>각각</em> 점수순일 뿐 전역 점수순이 아니고, 옵티마이저가 그 위에 filesort를 얹는다.
     * 벤치 시나리오의 20%가 시 단위(`popular-city`)이므로 B의 존재 이유는 그 구간에 그대로
     * 적용되지 않는다 — <b>측정 전 EXPLAIN으로 {@code Using filesort} 유무를 확정하고 판정문에
     * 근거로 실을 것.</b> 정렬 대상이 시 단위 ≤1,800행이라 비용 자체는 LATEST에 대해 수용한 것과
     * 같은 규모이므로, 확정되더라도 설계를 바꿀 사유는 아니고 <em>주장의 범위</em>를 좁힐 사유다.
     *
     * <p><b>술어를 ps 컬럼으로 잡고 신선도는 조인으로 거르는 이유.</b> town_id·active는 places에서
     * 비정규화해 온 값이라 최대 24시간 낡을 수 있다(V24 주석). 그럼에도 WHERE를 ps 쪽에 거는 것은
     * places와 JOIN한 조건으로는 위 인덱스가 정렬에 쓰이지 못해 이 경로의 존재 이유가 사라지기
     * 때문이다. 대신 {@code JOIN places p ON ... AND p.active = 1}이 <b>내려간 장소</b>는 즉시
     * 걸러 준다 — 노출돼선 안 될 장소가 하루 더 보이는 쪽이 correctness 문제이므로 그쪽만 막는다.
     *
     * <p><b>단 조인 가드가 닫는 것은 active 축의 한쪽 방향뿐이다.</b> 남는 구멍은 둘이다.
     * <ol>
     *   <li><b>재활성화 누락</b> — {@code p.active=1}인데 {@code ps.active=0}인 상태, 즉 내렸던 장소나
     *       동네를 <em>다시 올린</em> 직후 다음 배치 전까지다. 이때 db 모드는 그 장소를 통째로
     *       누락하고, 캐시 모드는 {@code invalidateAfterCommit}으로 스냅샷을 다시 읽어 실제 점수로
     *       노출한다. 가상의 경로가 아니다 — {@code AdminPlaceService.activatePlacesByTownIds}가
     *       {@code updateActiveByTownId(townIds, true)}로 동네 단위 일괄 재활성화를 한다.
     *       즉 동네 하나가 인기순에서 최대 24시간 사라질 수 있고, 이는 "내려간 장소가 더 보인다"보다
     *       눈에 띄는 방향이다.</li>
     *   <li><b>동네 이동</b> — 동네를 옮긴 장소가 최대 24시간 이전 동네에 나타난다. 이는 순위가 아니라
     *       소속의 문제라 다음 배치가 정정한다.</li>
     * </ol>
     * 둘 다 다음 배치가 자동으로 메우므로 창은 ≤24h이고, 어느 쪽도 쿼리로는 못 막는다
     * (막으려면 술어를 places로 옮겨야 하는데 그러면 인덱스를 잃는다). 실제 해법은 어드민 변경 시
     * place_stats를 동기 갱신하는 훅이며 그것이 <b>플랜 C Q3</b>다 — 측정 문서에 기록할 것.
     *
     * <p><b>커서 점수를 double로 바인딩하는 이유.</b> 캐시 경로가 커서에 싣는 값은
     * {@code DECIMAL(18,6)}을 {@code doubleValue()}로 좁힌 것이다. MySQL은 DECIMAL 컬럼과 DOUBLE
     * 파라미터를 비교할 때 양쪽을 DOUBLE로 올려 비교하므로, 자바가 커서를 만들 때 겪은 것과 같은
     * 좁힘을 SQL도 겪는다 — 즉 경계에서의 등가(=)가 양쪽에서 똑같이 판정된다. BigDecimal로
     * 바인딩하면 오히려 자바가 이미 뭉갠 값을 DB만 정확히 비교해 커서 경계가 어긋난다.
     *
     * <p>표시 카운트({@code ps.bookmark_count})를 같은 SELECT에 실어 추가 조회를 0으로 둔다.
     * 표시 보정은 2026-07-31에 제거됐으므로 읽은 값을 그대로 내보내면 된다.
     *
     * <p><b>⚠️ 모드 간 응답 등가의 유일한 예외 — ps 행이 없는 장소.</b> 이 쿼리는 place_stats를
     * <em>기준 테이블</em>로 잡으므로 행이 없는 장소를 아예 반환하지 않는다. 캐시 경로는 반대로
     * 스냅샷의 전 장소를 후보로 삼고 점수가 없으면 0점으로 쳐서({@code PlaceListPaginator.sortKeyOf}의
     * {@code getOrDefault(id, 0.0)}) 목록 <b>맨 뒤</b>에 포함시킨다. 따라서 "배치도 증분도 아직 닿지
     * 않은 신규 장소"에서만 두 모드의 결과 집합이 갈린다 (캐시=포함, db=누락).
     *
     * <p>기준 테이블을 places로 뒤집어 {@code LEFT JOIN place_stats}로 맞출 수도 있지만 그러지
     * 않는다 — 그 순간 {@code idx_place_stats_town_score}가 정렬에 쓰이지 못해 이 경로의 존재 이유
     * (정렬을 인덱스가 흡수한다)가 통째로 사라진다. 즉 이것은 버그가 아니라 <b>B가 사는 방식의
     * 대가</b>이고, 대가의 크기는 "활동 0인 장소가 인기순 꼬리에서 최대 24시간 빠진다"이다.
     * 배치가 전 장소에 행을 남기므로(부팅 최초 적재 + 매일 02:00 전량 재계산) 구멍은 마지막 배치
     * 이후 <em>새로 생긴</em> 장소로 한정된다.
     *
     * <p>A/B 벤치의 정합성 diff 게이트는 이 예외 위에서만 성립한다 — <b>모든 active 장소가 ps 행을
     * 가진 상태(배치 직후)에서 돌려야</b> diff가 비고, 그렇지 않은데 비었다면 그건 게이트가
     * 통과한 게 아니라 게이트가 아무것도 보지 않은 것이다. LATEST는 기준 테이블이 places라
     * 이 예외가 없다 — 같은 파일 안에서 두 정렬의 기준 테이블이 비대칭인 것은 의도된 것이다.
     *
     * @param cursorScore   커서의 sortKey(popular_score). null이면 첫 페이지
     * @param cursorPlaceId 커서의 장소 id. null이면 첫 페이지
     */
    @SuppressWarnings("unchecked")
    public List<PopularRow> findPopularRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Double cursorScore, Long cursorPlaceId, int limit) {

        boolean useMainTag = mainTagId != null;
        boolean useSubA = useMainTag && subTagAIds != null && !subTagAIds.isEmpty();
        boolean useSubB = useMainTag && subTagBIds != null && !subTagBIds.isEmpty();
        boolean useCursor = cursorScore != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id, ps.popular_score, ps.bookmark_count
                FROM place_stats ps
                JOIN places p ON p.id = ps.place_id AND p.active = 1
                WHERE ps.town_id IN (:townIds)
                  AND ps.active = 1
                """);
        appendTagFilters(sql, useMainTag, useSubA, useSubB);
        if (useCursor) {
            sql.append("""
                      AND (ps.popular_score < :cursorScore
                           OR (ps.popular_score = :cursorScore AND ps.place_id > :cursorPlaceId))
                    """);
        }
        sql.append("ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT :limitSize");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", townIds)
                .setParameter("limitSize", limit);
        bindTagFilters(query, mainTagId, subTagAIds, subTagBIds, useMainTag, useSubA, useSubB);
        if (useCursor) {
            query.setParameter("cursorScore", cursorScore);
            query.setParameter("cursorPlaceId", cursorPlaceId);
        }

        List<Object[]> rows = query.getResultList();
        List<PopularRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new PopularRow(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).longValue()));
        }
        return result;
    }

    /**
     * 최신순. <b>기준 테이블이 place_stats가 아니라 places다</b> — 배치가 아직 닿지 않은 신규 장소는
     * ps 행이 없는데, 신규 장소야말로 최신순의 맨 앞에 와야 할 대상이다. 그래서 카운트만
     * LEFT JOIN으로 붙이고 없으면 0으로 읽는다 (캐시 경로도 행이 없으면 0으로 표시한다).
     *
     * <p><b>이 정렬에 새 인덱스를 만들지 않는다.</b> 정렬 대상은 동네당 100건 안팎, 시 단위로 합쳐도
     * 1,800건 수준(실측)이라 filesort 비용이 캐시 경로의 메모리 정렬과 같은 규모다. 인덱스를 더하면
     * 쓰기 비용만 늘고 비교 대상 B의 성격("정렬을 DB에 맡긴다")도 흐려진다.
     *
     * <p><b>커서를 {@code FROM_UNIXTIME}이 아니라 LocalDateTime 바인딩으로 비교하는 이유.</b>
     * {@code FROM_UNIXTIME}은 세션 {@code time_zone}을 타므로 커넥션 설정에 따라 경계가 통째로
     * 밀린다 — 커서 초는 UTC 기준인데 세션이 KST면 9시간이 어긋난다. 반면 커서의 epoch 초는 캐시
     * 경로가 {@code createdAt.toEpochSecond(ZoneOffset.UTC)}로 만든, 벽시계 값을 UTC로 <em>간주해</em>
     * 얻은 수다. 그러므로 정확히 그 역변환({@code LocalDateTime.ofEpochSecond(sec, 0, UTC)})으로
     * 원래 벽시계 값을 복원해 DATETIME 컬럼과 직접 비교하는 것이, 타임존에 의존하지 않으면서
     * 캐시 경로와 왕복이 정확히 일치하는 유일한 방식이다.
     *
     * <p>{@code places.created_at}은 초 정밀도 DATETIME이라 같은 초에 여러 장소가 들어올 수 있다.
     * 그래서 등호 분기의 타이브레이크({@code p.id < :cursorPlaceId})가 필수다 — 없으면 같은 초의
     * 장소들이 페이지 경계에서 조용히 누락된다.
     *
     * @param cursorEpochSecond 커서의 sortKey(생성일 epoch 초, UTC 기준). null이면 첫 페이지
     * @param cursorPlaceId     커서의 장소 id. null이면 첫 페이지
     */
    @SuppressWarnings("unchecked")
    public List<LatestRow> findLatestRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Long cursorEpochSecond, Long cursorPlaceId, int limit) {

        boolean useMainTag = mainTagId != null;
        boolean useSubA = useMainTag && subTagAIds != null && !subTagAIds.isEmpty();
        boolean useSubB = useMainTag && subTagBIds != null && !subTagBIds.isEmpty();
        boolean useCursor = cursorEpochSecond != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0)
                FROM places p
                LEFT JOIN place_stats ps ON ps.place_id = p.id
                WHERE p.town_id IN (:townIds)
                  AND p.active = 1
                """);
        appendTagFilters(sql, useMainTag, useSubA, useSubB);
        if (useCursor) {
            sql.append("""
                      AND (p.created_at < :cursorCreatedAt
                           OR (p.created_at = :cursorCreatedAt AND p.id < :cursorPlaceId))
                    """);
        }
        sql.append("ORDER BY p.created_at DESC, p.id DESC LIMIT :limitSize");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", townIds)
                .setParameter("limitSize", limit);
        bindTagFilters(query, mainTagId, subTagAIds, subTagBIds, useMainTag, useSubA, useSubB);
        if (useCursor) {
            query.setParameter("cursorCreatedAt",
                    LocalDateTime.ofEpochSecond(cursorEpochSecond, 0, ZoneOffset.UTC));
            query.setParameter("cursorPlaceId", cursorPlaceId);
        }

        List<Object[]> rows = query.getResultList();
        List<LatestRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new LatestRow(
                    ((Number) row[0]).longValue(),
                    toLocalDateTime(row[1]),
                    ((Number) row[2]).longValue()));
        }
        return result;
    }

    /**
     * 태그 EXISTS 블록. 두 정렬이 같은 문자열을 <b>공유</b>해야 "정렬 축만 다르고 필터 의미론은 같다"가
     * 구조적으로 보장된다 — 복사해 두면 한쪽만 고치는 실수가 조용히 통과한다.
     * 기준 별칭이 {@code p}인 것은 두 쿼리 모두 places를 {@code p}로 두기 때문이다.
     *
     * <p><b>캐시 경로와의 차이 — 태그 타입을 여기서는 검사하지 않는다.</b> {@code CachedPlaceFilter}는
     * 스냅샷이 이미 타입별 버킷({@code activeMainTagIds}/{@code activeOption1TagIds}/
     * {@code activeOption2TagIds})으로 쪼개져 있어 "메인 자리에 온 id가 실제로 MAIN인가"를
     * 구조적으로 확인하지만, 여기 EXISTS는 {@code t.id}와 {@code t.active}만 본다. 따라서
     * <b>타입이 어긋난 입력</b>(메인 자리에 OPTION 태그 id 등)에서는 캐시가 0건, db가 매칭이 되어
     * 두 모드가 갈린다.
     *
     * <p>그럼에도 타입 검사를 더하지 않는 이유는, 상위 {@code TagValidator.validatePlaceTagConditions}가
     * 이미 그 조합을 400으로 막아 서비스에 도달하는 입력에는 타입 위반이 없기 때문이다. 여기서 또
     * 검사하면 인덱스를 타는 EXISTS에 tags 컬럼 조건이 하나 더 붙어 B의 비용만 늘고, 막는 것은
     * 이미 막힌 입력이다. 대신 <b>A/B diff 게이트를 돌릴 때 요청 태그 조합이 타입 규약을 지키는지가
     * 전제</b>이며, 이를 측정 문서에 남길 것.
     */
    private void appendTagFilters(
            StringBuilder sql, boolean useMainTag, boolean useSubA, boolean useSubB) {
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
    }

    private void bindTagFilters(
            Query query, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            boolean useMainTag, boolean useSubA, boolean useSubB) {
        if (useMainTag) {
            query.setParameter("mainTagId", mainTagId);
        }
        if (useSubA) {
            query.setParameter("subTagAIds", subTagAIds);
        }
        if (useSubB) {
            query.setParameter("subTagBIds", subTagBIds);
        }
    }

    /**
     * DATETIME 컬럼의 반환 타입은 드라이버·하이버네이트 조합에 따라 {@code Timestamp}와
     * {@code LocalDateTime}으로 갈린다 ({@code PlaceStatsRepositoryIT}가 BOOLEAN에서 같은 변주를
     * 다룬다). 어느 쪽이든 <b>벽시계 값 그대로</b> 받는 것이 중요하다 — 여기서 타임존 변환이 끼면
     * 호출자가 만드는 커서 초가 저장값과 어긋나 페이징이 깨진다.
     */
    private LocalDateTime toLocalDateTime(Object value) {
        return value instanceof LocalDateTime ldt ? ldt : ((Timestamp) value).toLocalDateTime();
    }
}
