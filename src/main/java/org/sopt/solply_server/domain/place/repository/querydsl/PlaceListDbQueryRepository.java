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
 * 장소 목록을 DB에서 정렬·필터·페이징한다 — 목록 조회의 <b>유일한</b> 경로다.
 *
 * <p><b>여기까지 온 경위.</b> 2026-08-01까지 이 클래스는 A/B 실측의 B였다. A는 동네별 스냅샷을
 * 메모리에 들고 앱에서 정렬하는 캐시 경로였고, 두 경로가 {@code place_stats.popular_score}라는
 * 같은 랭킹 소스를 써서 응답이 동일해야 한다는 성질이 벤치의 검증 장치였다. 판정은 B였고
 * ({@code docs/perf/2026-08-01-cache-vs-db-direct.md}) 캐시는 철거됐다.
 *
 * <p><b>커서 계약.</b> {@code PlaceListCursor} v2를 그대로 쓴다 — 코덱이 캐시 시절과 같으므로
 * 철거 배포 중 스크롤하던 클라이언트의 커서도 거부되지 않는다. sortKey는 POPULAR이 popular_score의
 * double, LATEST가 createdAt의 epoch 초(UTC)이고, 정렬 규칙은 POPULAR (점수 DESC, id ASC) /
 * LATEST (생성일 DESC, id DESC)다. 발급하는 쪽({@code PlaceService})과 해석하는 쪽(여기)이
 * 한 쌍이라 한쪽만 바꾸면 페이징이 조용히 어긋난다.
 *
 * <p>태그 필터 의미론은 북마크 검색의 {@code PlaceTagMatcher}와 같다 (타입 내 OR, 타입 간 AND,
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
     * (town_id, popular_score DESC, place_id, bookmark_count)가 필터·정렬·타이브레이크를 흡수하고,
     * 끝의 {@code bookmark_count}가 커버링까지 만든다 (V26).
     *
     * <p><b>단 "정렬을 흡수한다"가 성립하는 것은 동네(leaf) 단위 조회뿐이다 — 2026-08-01 EXPLAIN
     * 실측으로 확정.</b> 술어가 {@code town_id IN (:townIds)}라 town이 여러 개면 town별 range가
     * <em>각각</em> 점수순일 뿐 전역 점수순이 아니고, 옵티마이저는 그 위에 filesort를 얹는 대신
     * <b>인덱스를 통째로 포기하고 place_stats를 풀스캔</b>한다.
     *
     * <table>
     *   <caption>벤치 DB(장소 6,320) 실측 — {@code results/explain/popular-sort-plan.txt}</caption>
     *   <tr><th>조회</th><th>접근</th><th>정렬</th><th>소요</th></tr>
     *   <tr><td>동네 1개</td><td>{@code ref} · idx_place_stats_town_score</td><td>없음</td><td>0.324ms</td></tr>
     *   <tr><td>시(leaf 18)</td><td>{@code ALL} · key=NULL, 6,320행 스캔</td><td>filesort</td><td>6.46ms</td></tr>
     * </table>
     *
     * <p>스캔 대상이 그 시의 1,800행이 아니라 <b>테이블 전체</b>라는 점이 중요하다 — 서울 조회
     * 비용이 부산 장소 수에도 비례한다. 지금은 6,320행이라 6.46ms지만 전국 규모로 늘면 선형으로
     * 커진다. 시 단위 조회가 시나리오의 40%(`popular-city` 20 + `popular-scroll` 20)이므로 이것이
     * B의 실제 성격이며, <b>"정렬을 인덱스가 흡수한다"는 동네 단위에 한정해서만 말할 수 있다.</b>
     *
     * <p><b>왜 행을 3.6배 적게 읽는 쪽이 더 비싼가</b> — 옵티마이저 trace가 직접 답한다
     * (range scan cost 1,947.51 vs 풀스캔 673.32, {@code cause: "cost"}). 이유가 둘 겹친다.
     * <ol>
     *   <li><b>다중 range가 정렬 가치를 파괴한다.</b> town이 하나면 인덱스가 이미 점수순이라
     *       21건만 읽고 멈춘다(LIMIT 단락). 18개면 각 range가 <em>각자</em> 정렬돼 있을 뿐이라
     *       전역 정렬을 위해 1,800건을 전부 꺼내야 하고, LIMIT이 일을 못 한다.</li>
     *   <li><b>인덱스가 커버링이 아니라 그 1,800건이 전부 랜덤 접근이다.</b> 인덱스에
     *       {@code bookmark_count}가 없어 엔트리마다 클러스터드 인덱스로 되돌아간다.
     *       랜덤 1,800회 &gt; 순차 6,440행 — 그래서 풀스캔이 이긴다.</li>
     * </ol>
     *
     * <p><b>②는 V26이 없앴다 — 인덱스 끝에 {@code bookmark_count}를 덧붙여 커버링으로 만들었다.
     * 실측(2026-08-01): 6.46ms → 0.806ms, 8배.</b> 풀스캔이 인덱스 range scan으로 바뀌어 스캔
     * 대상이 테이블 전체가 아니라 그 시의 1,800행이 되므로, 위에 적은 "전국 규모로 선형 증가"라는
     * 확장성 문제도 함께 사라진다. ①은 남아 filesort는 계속되지만 정렬 대상이 인덱스 엔트리라
     * 훨씬 싸다. <b>위 표의 6.46ms는 V26 이전 인덱스에서 잰 값이다</b> — 판정 근거로 남긴다.
     *
     * <p>선두 프리픽스 {@code (town_id, popular_score DESC, place_id)}는 그대로라
     * <b>동네 단위는 하나도 잃지 않는다.</b> 즉 두 접근 패턴은 상충하지 않는다 — 한때 상충한다고
     * 적었으나 실측으로 뒤집혔다. 이 변경을 A/B 측정 직후가 아니라 캐시 제거와 함께 넣은 이유는,
     * 측정이 끝난 조건을 바꾸면 그 판정의 근거가 흔들리기 때문이다.
     *
     * <p><b>술어를 ps 컬럼으로 잡고 신선도는 조인으로 거르는 이유.</b> town_id는 places에서
     * 비정규화해 온 값이라 배치 간격만큼 낡을 수 있다(V24 주석). 그럼에도 WHERE를 ps 쪽에 거는 것은
     * places와 JOIN한 조건으로는 위 인덱스가 정렬에 쓰이지 못해 이 경로의 존재 이유가 사라지기
     * 때문이다.
     *
     * <p><b>active 축은 술어에서 빠졌다 — 진실은 {@code places.active} 하나다 (2026-08-02 결정).</b>
     * 이전에는 {@code AND ps.active = 1}이 함께 걸려 있었고, 조인 가드
     * ({@code JOIN places p ON ... AND p.active = 1})는 <b>내려간 장소</b>만 즉시 거르는 한쪽 방향
     * 가드였다. 그래서 반대 방향에 구멍이 있었다 — {@code p.active=1}인데 {@code ps.active=0}인
     * 상태, 즉 내렸던 장소나 동네를 <em>다시 올린</em> 직후 다음 배치 전까지 그 장소가 목록에서
     * 통째로 실종됐다. 가상의 경로가 아니다: {@code AdminPlaceService.activatePlacesByTownIds}가
     * {@code updateActiveByTownId(townIds, true)}로 동네 단위 일괄 재활성화를 한다.
     *
     * <p>ps 쪽 술어를 지운 근거는 <b>실익과 비용의 비대칭</b>이다. 즉시 숨김은 원래 조인 가드의
     * 몫이었으므로 {@code ps.active}의 실효는 인덱스 스캔 가지치기 하나뿐이었는데, 비활성 장소가
     * 희소해 그 이득은 ~0이다 (그리고 "프리픽스에 active가 있어 이득"이라는 주장은 비활성 행이
     * 많이 존재함을 전제한 순환 논리였다). 반면 낡음의 비용 — 재활성화 누락 — 은 실재했다.
     * 복제본을 없애면 낡을 것도 없다. V26이 컬럼 자체를 drop해 이 판단을 스키마로 굳힌다.
     *
     * <p><b>남는 구멍은 하나 — 동네 이동이다.</b> 동네를 옮긴 장소가 다음 배치까지 이전 동네에
     * 나타난다. 이는 순위가 아니라 소속의 문제이고, 쿼리로는 못 막는다(막으려면 술어를 places로
     * 옮겨야 하는데 그러면 인덱스를 잃는다). 어드민 동기 갱신 훅 대신 <b>배치 간격 단축</b>으로
     * 갈음한다 — 매시 30분이라 창은 ≤1h다 (2026-08-02, {@code PlaceStatsFacade} javadoc 참조).
     *
     * <p><b>커서 점수를 double로 바인딩하는 이유.</b> 커서에 싣는 값은
     * {@code DECIMAL(18,6)}을 {@code doubleValue()}로 좁힌 것이다. MySQL은 DECIMAL 컬럼과 DOUBLE
     * 파라미터를 비교할 때 양쪽을 DOUBLE로 올려 비교하므로, 자바가 커서를 만들 때 겪은 것과 같은
     * 좁힘을 SQL도 겪는다 — 즉 경계에서의 등가(=)가 양쪽에서 똑같이 판정된다. BigDecimal로
     * 바인딩하면 오히려 자바가 이미 뭉갠 값을 DB만 정확히 비교해 커서 경계가 어긋난다.
     *
     * <p>표시 카운트({@code ps.bookmark_count})를 같은 SELECT에 실어 추가 조회를 0으로 둔다.
     * 표시 보정은 2026-07-31에 제거됐으므로 읽은 값을 그대로 내보내면 된다.
     *
     * <p><b>⚠️ ps 행이 없는 장소는 인기순에 나오지 않는다.</b> 이 쿼리는 place_stats를
     * <em>기준 테이블</em>로 잡으므로 행이 없는 장소를 아예 반환하지 않는다. 즉 "배치도 증분도
     * 아직 닿지 않은 신규 장소"가 인기순에서 통째로 빠진다 (캐시 경로는 스냅샷의 전 장소를 후보로
     * 삼고 점수가 없으면 0점으로 쳐 맨 뒤에 포함시켰다 — 이 지점이 두 경로가 갈리던 유일한 곳이었다).
     *
     * <p>기준 테이블을 places로 뒤집어 {@code LEFT JOIN place_stats}로 맞출 수도 있지만 그러지
     * 않는다 — 그 순간 {@code idx_place_stats_town_score}가 정렬에 쓰이지 못해 이 경로의 존재 이유
     * (정렬을 인덱스가 흡수한다)가 통째로 사라진다. 즉 이것은 버그가 아니라 <b>대가</b>이고,
     * 대가의 크기는 "활동 0인 장소가 인기순 꼬리에서 다음 배치까지 빠진다"이다.
     * 배치가 전 장소에 행을 남기므로(부팅 최초 적재 + 매시 30분 전량 재계산) 구멍은 마지막 배치
     * 이후 <em>새로 생긴</em> 장소로 한정되고 창은 ≤1h다.
     *
     * <p>LATEST는 기준 테이블이 places라 이 예외가 없다 — 같은 파일 안에서 두 정렬의 기준 테이블이
     * 비대칭인 것은 의도된 것이다. 신규 장소야말로 최신순 맨 앞에 와야 할 대상이기 때문이다.
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
     * <p><b>북마크 검색 경로와의 차이 — 태그 타입을 여기서는 검사하지 않는다.</b>
     * {@code PlaceTagMatcher}는 엔티티의 {@code Tag.getType()}을 직접 보고 "메인 자리에 온 id가
     * 실제로 MAIN인가"를 확인하지만, 여기 EXISTS는 {@code t.id}와 {@code t.active}만 본다. 따라서
     * <b>타입이 어긋난 입력</b>(메인 자리에 OPTION 태그 id 등)에서는 북마크 검색이 0건,
     * 목록이 매칭이 되어 두 경로가 갈린다.
     *
     * <p>그럼에도 타입 검사를 더하지 않는 이유는, 상위 {@code TagValidator.validatePlaceTagConditions}가
     * 이미 그 조합을 400으로 막아 서비스에 도달하는 입력에는 타입 위반이 없기 때문이다. 여기서 또
     * 검사하면 인덱스를 타는 EXISTS에 tags 컬럼 조건이 하나 더 붙어 B의 비용만 늘고, 막는 것은
     * 이미 막힌 입력이다. 즉 위의 갈림은 <b>상위 검증이 통과시키지 않는 입력에서만</b> 관측된다.
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
