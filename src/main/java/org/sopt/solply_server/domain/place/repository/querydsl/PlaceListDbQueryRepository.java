package org.sopt.solply_server.domain.place.repository.querydsl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
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
 * <p><b>커서 계약.</b> {@code PlaceListCursor} v3. sortKey는 POPULAR이 점수의 double,
 * LATEST가 createdAt의 epoch 초(UTC)이고, 정렬은 POPULAR (점수 DESC, id ASC) /
 * LATEST (생성일 DESC, id DESC)다. 발급하는 쪽({@code PlaceService})과 해석하는 쪽(여기)이
 * 한 쌍이라 한쪽만 바꾸면 페이징이 조용히 어긋난다.
 *
 * <p>태그 필터 의미론은 북마크 검색의 {@code PlaceTagMatcher}와 같다 (타입 내 OR, 타입 간 AND,
 * 메인 태그가 없으면 서브 태그는 무시).
 *
 * <p>상세: {@code docs/design/2026-08-05-place-stats-version-rows.md} §4
 */
@Repository
@RequiredArgsConstructor
public class PlaceListDbQueryRepository {

    private final EntityManager em;

    /**
     * {@code avgRating}은 null을 유지한다 — "리뷰가 없다"와 "평점이 0이다"는 다른 말이고,
     * 여기서 0으로 뭉개면 응답까지 그 구분이 사라진다.
     */
    public record PopularRow(long placeId, double popularScore, long bookmarkCount,
                             long reviewCount, BigDecimal avgRating) {}

    public record LatestRow(long placeId, LocalDateTime createdAt, long bookmarkCount,
                            long reviewCount, BigDecimal avgRating) {}

    /**
     * 인기순을 place_stats 단독으로 서빙한다 — {@code idx_place_stats_version_town_score}
     * (version, town_id, popular_score DESC, place_id, bookmark_count, review_count, avg_rating)가
     * 버전 파티션·필터·정렬·타이브레이크를 흡수하고, 말단 세 컬럼이 표시값까지 덮어 커버링을
     * 만든다 (V29 → V30).
     *
     * <p><b>SELECT에 표시 컬럼을 더할 때는 인덱스 말단도 함께 늘린다.</b> 덮지 못한 컬럼이 하나라도
     * 있으면 페이지 행마다 {@code PRIMARY (place_id, version)} 룩업이 붙는다. 세컨더리 엔트리가
     * PK를 이미 들고 있어 등호 조회이긴 하나, 이 인덱스의 존재 이유가 "쿼리가 인덱스 안에서
     * 끝난다"이므로 조용히 깨뜨리지 말 것 (V30 주석에 채택 근거).
     *
     * <p><b>불변식: 한 버전의 행 집합 = 그 회차 시점의 활성 장소.</b> 그래서 여기서 활성 여부를
     * 묻지 않는다. 지키는 주체는 배치이고({@code upsertAll}의 {@code WHERE p.active = 1} +
     * {@code deleteVersionsOtherThan}) 대가는 노출 창 ≤1h다 — 비활성화도 재활성화도 다음 배치까지
     * 반영되지 않는다.
     *
     * <p><b>⚠️ ps 행이 없는 장소는 인기순에 나오지 않는다.</b> place_stats가 <em>기준 테이블</em>이라
     * 마지막 배치 이후 새로 생긴 장소가 통째로 빠진다(창 ≤1h). 기준을 places로 뒤집으면 정렬
     * 인덱스를 잃으므로 이것은 버그가 아니라 대가다. LATEST는 기준 테이블이 places라 이 예외가
     * 없다 — 신규 장소야말로 최신순 맨 앞에 와야 하기 때문이며, 두 정렬의 비대칭은 의도된 것이다.
     *
     * <p><b>다중 town 조회의 filesort는 수용한다.</b> 인덱스상 결과가 town별로 묶여 각 range 안에서만
     * 점수순이라 {@code town_id IN (...)}이 여러 개면 전역 점수순을 인덱스가 만들 수 없다.
     * 정렬 대상이 커버링 엔트리(시 단위 ~1,800건)라 싸다는 것이 수용 근거다(실측).
     *
     * <p><b>커서 점수를 double로 바인딩하는 이유.</b> 커서에 싣는 값은 {@code DECIMAL(18,6)}을
     * {@code doubleValue()}로 좁힌 것이고, MySQL도 DECIMAL과 DOUBLE 파라미터를 DOUBLE로 올려
     * 비교하므로 경계의 등가가 양쪽에서 똑같이 판정된다. BigDecimal로 바인딩하면 오히려 자바가
     * 이미 뭉갠 값을 DB만 정확히 비교해 경계가 어긋난다.
     *
     * <p>상세: {@code docs/design/2026-08-05-place-stats-version-rows.md} §4
     *
     * @param version       바인딩할 랭킹 버전. 커서가 있으면 커서의 것, 없으면 현 버전이다 —
     *                      판정은 호출자({@code PlaceService#resolveVersion})의 몫이다
     * @param cursorScore   커서의 sortKey. null이면 첫 페이지
     * @param cursorPlaceId 커서의 장소 id. null이면 첫 페이지
     */
    @SuppressWarnings("unchecked")
    public List<PopularRow> findPopularRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            long version, Double cursorScore, Long cursorPlaceId, int limit) {

        boolean useMainTag = mainTagId != null;
        boolean useSubA = useMainTag && subTagAIds != null && !subTagAIds.isEmpty();
        boolean useSubB = useMainTag && subTagBIds != null && !subTagBIds.isEmpty();
        boolean useCursor = cursorScore != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
                       ps.review_count, ps.avg_rating
                FROM place_stats ps
                WHERE ps.version = :version
                  AND ps.town_id IN (:townIds)
                """);
        appendTagFilters(sql, "ps.place_id", useMainTag, useSubA, useSubB);
        if (useCursor) {
            sql.append("""
                      AND (ps.popular_score < :cursorScore
                           OR (ps.popular_score = :cursorScore AND ps.place_id > :cursorPlaceId))
                    """);
        }
        sql.append("ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT :limitSize");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("version", version)
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
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    (BigDecimal) row[4]));
        }
        return result;
    }

    /**
     * 최신순. <b>기준 테이블이 place_stats가 아니라 places다</b> — 배치가 아직 닿지 않은 신규 장소는
     * ps 행이 없는데, 신규 장소야말로 최신순의 맨 앞에 와야 할 대상이다. 그래서 카운트만
     * LEFT JOIN으로 붙이고 없으면 0으로 읽는다 (캐시 경로도 행이 없으면 0으로 표시한다).
     *
     * <p><b>{@code avg_rating}에만 COALESCE를 걸지 않는다.</b> 카운트는 없으면 0이 정확한 답이지만
     * 평점은 0으로 채우는 순간 "평점 0점"으로 읽힌다. 조인이 성립하지 않은 신규 장소도, 리뷰가
     * 아직 없는 장소도 null이 정답이라 그대로 흘려보낸다.
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
     * <p><b>조인 조건의 버전 스칼라 서브쿼리를 지우지 말 것.</b> 지우면 보관 중인 두 버전이 모두
     * 붙어 장소마다 행이 2개로 펼쳐진다. 메타를 따로 읽는 대신 같은 문장에 접합해 이 경로의
     * 요청당 SQL 수를 유지한다 — PK 1행 조회라 MySQL이 상수로 한 번만 평가한다.
     * 배치 전이라 현 버전이 NULL이면 조인이 성립하지 않아 카운트가 0이 되는데, 그것이 정확한 답이다.
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
                SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
                       COALESCE(ps.review_count, 0), ps.avg_rating
                FROM places p
                LEFT JOIN place_stats ps
                       ON ps.place_id = p.id
                      AND ps.version = (SELECT current_generation
                                          FROM place_stats_meta WHERE id = 1)
                WHERE p.town_id IN (:townIds)
                  AND p.active = 1
                """);
        appendTagFilters(sql, "p.id", useMainTag, useSubA, useSubB);
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
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    (BigDecimal) row[4]));
        }
        return result;
    }

    /**
     * 태그 EXISTS 블록. 두 정렬이 같은 문자열을 <b>공유</b>해야 "정렬 축만 다르고 필터 의미론은 같다"가
     * 구조적으로 보장된다 — 복사해 두면 한쪽만 고치는 실수가 조용히 통과한다.
     *
     * <p><b>장소 id 컬럼을 별칭째 파라미터로 받는 이유 — 공유를 지키기 위해서다.</b> 예전에는 두 쿼리
     * 모두 places를 {@code p}로 뒀기에 {@code p.id}를 문자열에 박아 둘 수 있었다. 인기순에서 places
     * 조인이 사라지면서({@link #findPopularRows} javadoc) 그 별칭이 한쪽에만 존재하게 됐는데,
     * 그때 선택지는 둘이었다 — 블록을 복사해 정렬별로 갈라 두거나, 다른 <em>한 조각</em>만
     * 파라미터로 빼고 템플릿은 한 벌로 남기거나. 갈라 두면 위 문단의 구조적 보장이 그대로
     * 사라지므로 후자를 택했다. 인기순은 {@code "ps.place_id"}, 최신순은 {@code "p.id"}를 넘긴다.
     *
     * <p>이 인자는 <b>호출자가 주는 컬럼 표현식 리터럴</b>이라 사용자 입력이 닿지 않는다 —
     * 두 호출부 모두 상수 문자열이다. 여기에 요청에서 온 값을 흘리는 순간 SQL 조립이 되므로
     * 그러지 말 것 (태그 id들은 지금처럼 {@code :mainTagId} 같은 바인딩 파라미터로만 들어온다).
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
            StringBuilder sql, String placeIdColumn,
            boolean useMainTag, boolean useSubA, boolean useSubB) {
        if (useMainTag) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
                                   WHERE pt.place_id = %s AND t.id = :mainTagId AND t.active = 1)
                    """.formatted(placeIdColumn));
        }
        if (useSubA) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
                                   WHERE pt.place_id = %s AND t.id IN (:subTagAIds) AND t.active = 1)
                    """.formatted(placeIdColumn));
        }
        if (useSubB) {
            sql.append("""
                      AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
                                   WHERE pt.place_id = %s AND t.id IN (:subTagBIds) AND t.active = 1)
                    """.formatted(placeIdColumn));
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
