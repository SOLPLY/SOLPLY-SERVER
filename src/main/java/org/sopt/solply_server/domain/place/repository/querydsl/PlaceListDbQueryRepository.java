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
import org.sopt.solply_server.domain.place.util.TagBitmask;
import org.springframework.stereotype.Repository;

/**
 * 장소 목록을 DB에서 정렬·필터·페이징한다 — 목록 조회의 <b>유일한</b> 경로다.
 *
 * <p><b>정적 정렬 다섯은 place_stats 단독이다 (V34·V36).</b> 기준 테이블도, 정렬 축도, 필터 축도,
 * 표시값도 전부 한 테이블 안에 있어 <b>조인이 하나도 없다.</b> 그것이 이 설계의 목적 그 자체다 — 조인이
 * 있으면 옵티마이저에게 조인 순서·세미조인 전략의 자유도가 생기고, 그 선택은 LIMIT 조기 종료를
 * 비용에 세지 못하는 맹점에 노출돼 입력(태그 규모 × 지역 크기)에 따라 계획이 흔들린다. 조건부
 * 힌트로 그 맹점을 우회하는 안은 임계가 요청 형상 한 점에서만 유효함이 실측으로 확정돼 철회했고
 * ({@code load-test/campaigns/2026-08-11_region-size-threshold}), 대신 선택지 자체를 없앴다.
 *
 * <p><b>거리순만 예외다 — {@code places}와 조인한다.</b> 좌표는 place_stats에 없고, 비정규화해도
 * 인덱스가 만들어 줄 수 있는 순서가 없다(기준점이 요청마다 다르다). 그래서 이 경로는 정렬·LIMIT을
 * DB에 맡기지 않고 <b>후보 전량을 커버링으로 훑어</b> 앱에서 정렬한다. 옵티마이저에게 남는 선택지가
 * 없다는 성질은 여기서도 유지된다 — LIMIT이 없으니 "조기 종료를 못 세는 맹점"이 애초에 성립하지
 * 않는다.
 *
 * <p><b>불변식: place_stats에 행이 있는 장소 = 목록에 나와도 되는 장소.</b> 그래서 어느 쿼리도
 * {@code places}를 되짚어 활성 여부를 묻지 않는다(거리순의 조인도 좌표만 읽는다).
 * 지키는 주체는 어드민 쓰기 경로 하나다 —
 * 생성·수정·재활성이 행을 짓고({@code PlaceStatsRepository#upsertRowsForActivePlaces}의
 * {@code WHERE p.active = 1}), 삭제가 그 자리에서 행을 지운다
 * ({@code AdminPlaceService#deletePlace}). <b>노출 창은 양쪽 다 즉시</b>다.
 * 두 배치는 값 칸만 정할 뿐 행의 존재에 관여하지 않는다.
 *
 * <p><b>커서 계약.</b> {@code PlaceListCursor} v5 — 정렬 키가 <b>튜플</b>이다. 정렬과 커서 키:
 * POPULAR (점수 DESC, id ASC / 키: 점수) · LATEST (생성일 DESC, id DESC / 키: epoch 초 UTC) ·
 * RATING (평점 DESC, 리뷰 수 DESC, id ASC / 키: 평점·리뷰 수) ·
 * REVIEW_COUNT·BOOKMARK_COUNT (카운트 DESC, id ASC / 키: 카운트) ·
 * DISTANCE (거리 ASC, id ASC / 키: 기준 위도·경도·거리, 정렬은 여기가 아니라 앱에서).
 * 발급하는 쪽({@code PlaceService})과 해석하는 쪽(여기)이 한 쌍이라 한쪽만 바꾸면 페이징이
 * 조용히 어긋난다.
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
     * {@code avgRating}은 컬럼 값 그대로다 — V37부터 NOT NULL이고 리뷰가 없으면 0이다.
     * "리뷰 없음"으로 되돌리는 것은 응답을 만드는 쪽의 일이다 ({@code PlacePreviewDto#of}).
     */
    public record PopularRow(long placeId, double popularScore, long bookmarkCount,
                             long reviewCount, BigDecimal avgRating) {}

    public record LatestRow(long placeId, LocalDateTime createdAt, long bookmarkCount,
                            long reviewCount, BigDecimal avgRating) {}

    /**
     * 인기순. {@code idx_place_stats_town_score} (town_id, popular_score DESC, place_id,
     * bookmark_count, review_count, avg_rating, score_calculated_at, tag_bitmask)가 필터·정렬·
     * 타이브레이크를 흡수하고, 말단 다섯 컬럼이 표시값·채점 여부·태그 소속까지 덮어 커버링을 만든다.
     *
     * <p><b>SELECT나 WHERE에 place_stats 컬럼을 더할 때는 인덱스 말단도 함께 늘린다.</b> 덮지 못한
     * 컬럼이 하나라도 있으면 행마다 {@code PRIMARY (place_id)} 룩업이 붙는다 — 이 인덱스의 존재
     * 이유가 "쿼리가 인덱스 안에서 끝난다"이므로 조용히 깨뜨리지 말 것. WHERE에만 있고 인덱스에
     * 없으면 <b>걸러낼 행마다</b> 룩업이 붙어 더 나쁘다 (V30·V32·V34 주석에 채택 근거).
     *
     * <p><b>⚠️ ps 행이 없는 장소는 인기순에 나오지 않는다.</b> place_stats가 <em>기준 테이블</em>이라
     * 어드민 경로를 지나쳐 생긴 장소가 통째로 빠진다(창 ≤1h). 어드민 생성·재활성은 같은
     * 트랜잭션에서 행을 만들므로 그 경로에는 창이 없다 ({@code AdminPlaceService}).
     *
     * <p><b>⚠️ {@code score_calculated_at IS NOT NULL}을 지우지 말 것 — 인기순은 채점된 행만 본다.</b>
     * 새로 만들어진 행의 {@code popular_score}는 컬럼 기본값 0인데, 그 0은 "점수가 0이다"가 아니라
     * <b>"아직 점수가 없다"</b>는 뜻이다. 술어를 지우면 그 행이 <em>유효한 음수 점수</em>보다 위에
     * 끼어든다 — 저평점 리뷰가 쌓인 장소의 점수는 실제로 음수가 되므로(리뷰 축이
     * {@code w₂ × (조정평점 − C)}라 {@code C} 아래면 음수), 아직 아무 평가도 받지 않은 신규 장소가
     * 평판 나쁜 장소를 제치고 올라간다. 두 값의 의미가 다른데 컬럼 하나로는 구분되지 않으므로
     * <b>{@code score_calculated_at}의 non-NULL이 유일한 판정 근거</b>다.
     *
     * <p>그래서 신규·재활성 장소는 <b>다음 인기점수 배치(새벽 01:00)까지 인기순에서 빠진다</b> —
     * 창의 상한이 24시간이다. 같은 장소가 최신순에는 즉시 나온다({@link #findLatestRows}는 이
     * 술어를 걸지 않는다). "인기순에서 24시간 빠진다"와 "잘못된 순위로 24시간 노출된다" 중 앞을
     * 고른 결정이며, 인기점수를 새벽 배치 이후 값만 관리한다는 원칙의 직접적 귀결이다.
     * <b>시간당 미채점 행만 따로 채점하는 패스를 추가하지 말 것</b> — 그 순간 "점수는 하루 1회"가
     * 깨지고 커서 좌표계가 다시 매시간 갈린다.
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
     * @param cursorScore   커서의 sortKey. null이면 첫 페이지
     * @param cursorPlaceId 커서의 장소 id. null이면 첫 페이지
     */
    @SuppressWarnings("unchecked")
    public List<PopularRow> findPopularRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Double cursorScore, Long cursorPlaceId, int limit) {

        TagMasks masks = TagMasks.of(mainTagId, subTagAIds, subTagBIds);
        boolean useCursor = cursorScore != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
                       ps.review_count, ps.avg_rating
                FROM place_stats ps
                WHERE ps.town_id IN (:townIds)
                  AND ps.score_calculated_at IS NOT NULL
                """);
        appendTagFilters(sql, masks);
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
        bindTagFilters(query, masks);
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
     * 최신순. 인기순과 <b>같은 기준 테이블</b>이고 정렬 축만 다르다 (V34).
     *
     * <p><b>{@code score_calculated_at} 술어를 여기에 넣지 말 것.</b> 신규 장소는 아직 미채점인데,
     * 신규 장소야말로 최신순의 맨 앞에 와야 할 대상이다. 인기순만 그 술어를 거는 비대칭이 의도다 —
     * 근거는 {@link #findPopularRows} javadoc.
     *
     * <p><b>정렬은 {@code idx_place_stats_town_created (town_id, created_at, place_id, ...)}가
     * 만든다 — 역방향 스캔이다.</b> 이 인덱스를 거꾸로 읽으면 {@code created_at DESC, place_id DESC}가
     * 그대로 나와 ORDER BY와 일치한다. {@code created_at}을 <b>DESC로 선언하면 오히려 어긋난다</b>
     * (뒤에 붙는 place_id가 여전히 오름차순이라 타이브레이크가 반대) — V31·V34 주석에 실측 근거가
     * 있다. 말단 네 컬럼이 태그 술어와 표시값을 덮어 커버링이기도 하다.
     *
     * <p>다중 town은 town별로만 순서가 만들어져 filesort가 남는다. 정렬 대상이 커버링 엔트리
     * (시 단위 ~1,800건)라 수용한다 — 인기순과 같은 성질이다.
     *
     * <p><b>커서를 {@code FROM_UNIXTIME}이 아니라 LocalDateTime 바인딩으로 비교하는 이유.</b>
     * {@code FROM_UNIXTIME}은 세션 {@code time_zone}을 타므로 커넥션 설정에 따라 경계가 통째로
     * 밀린다 — 커서 초는 UTC 기준인데 세션이 KST면 9시간이 어긋난다. 반면 커서의 epoch 초는 호출부가
     * {@code createdAt.toEpochSecond(ZoneOffset.UTC)}로 만든, 벽시계 값을 UTC로 <em>간주해</em> 얻은
     * 수다. 그러므로 정확히 그 역변환({@code LocalDateTime.ofEpochSecond(sec, 0, UTC)})으로 원래
     * 벽시계 값을 복원해 DATETIME 컬럼과 직접 비교하는 것이, 타임존에 의존하지 않으면서 발급부와
     * 왕복이 정확히 일치하는 유일한 방식이다.
     *
     * <p>{@code created_at}은 초 정밀도 DATETIME이라 같은 초에 여러 장소가 들어올 수 있다.
     * 그래서 등호 분기의 타이브레이크({@code ps.place_id < :cursorPlaceId})가 필수다 — 없으면 같은
     * 초의 장소들이 페이지 경계에서 조용히 누락된다.
     *
     * <p>표시 세 값 어디에도 COALESCE를 걸지 않는다 — 셋 다 {@code NOT NULL}이다
     * ({@code avg_rating}은 V37부터, 리뷰가 없으면 0).
     *
     * @param cursorEpochSecond 커서의 sortKey(생성일 epoch 초, UTC 기준). null이면 첫 페이지
     * @param cursorPlaceId     커서의 장소 id. null이면 첫 페이지
     */
    @SuppressWarnings("unchecked")
    public List<LatestRow> findLatestRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Long cursorEpochSecond, Long cursorPlaceId, int limit) {

        TagMasks masks = TagMasks.of(mainTagId, subTagAIds, subTagBIds);
        boolean useCursor = cursorEpochSecond != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id, ps.created_at, ps.bookmark_count,
                       ps.review_count, ps.avg_rating
                FROM place_stats ps
                WHERE ps.town_id IN (:townIds)
                """);
        appendTagFilters(sql, masks);
        if (useCursor) {
            sql.append("""
                      AND (ps.created_at < :cursorCreatedAt
                           OR (ps.created_at = :cursorCreatedAt AND ps.place_id < :cursorPlaceId))
                    """);
        }
        sql.append("ORDER BY ps.created_at DESC, ps.place_id DESC LIMIT :limitSize");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", townIds)
                .setParameter("limitSize", limit);
        bindTagFilters(query, masks);
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
     * 평점순 row. 정렬 키가 둘(평점·리뷰 수)이라 그 둘이 앞자리에 온다.
     * {@code avgRating}은 <b>절대 null이 아니다</b> — 컬럼이 NOT NULL이고 리뷰 0건이면 0이다 (V37).
     */
    public record RatingRow(long placeId, BigDecimal avgRating, long reviewCount,
                            long bookmarkCount) {}

    /** 리뷰 수·북마크 수 정렬의 공용 row — 정렬 키가 이미 표시값 안에 있어 따로 실을 것이 없다 */
    public record CountRow(long placeId, long bookmarkCount, long reviewCount,
                           BigDecimal avgRating) {}

    /** 거리순 후보. 정렬은 앱이 하므로 여기서는 좌표와 표시값만 실어 나른다 */
    public record DistanceCandidateRow(long placeId, double latitude, double longitude,
                                       long bookmarkCount, long reviewCount, BigDecimal avgRating) {}

    /**
     * 평점 높은 순. {@code idx_place_stats_town_rating}
     * (town_id, avg_rating DESC, review_count DESC, place_id, tag_bitmask, bookmark_count)가
     * 필터·정렬·타이브레이크를 흡수하고 말단 둘이 커버링을 만든다 (V36).
     *
     * <p><b>술어가 하나도 없다 — 리뷰 0건 장소도 0점으로 맨 뒤에 실린다 (V37).</b> 하루 전까지는
     * 여기에 {@code avg_rating IS NOT NULL}이 있었다. 리뷰가 없는 장소의 평점이 NULL이라
     * 커서 seek이 NULL 비교로 전부 무너져 그 행들이 두 번째 페이지부터 조용히 사라졌고, 어차피
     * 못 실을 바에는 술어로 끊는 편이 정직하다는 판단이었다. 뒤집은 것은 프로덕트 결정이다 —
     * 리뷰가 아직 없는 장소도 목록에 보여야 한다. 그래서 <b>저장을 NOT NULL 0으로 바꿔</b>
     * 사라지는 원인 자체를 없앴다(조회에 {@code COALESCE}를 씌우는 안은 정렬식이 컬럼이 아니게
     * 되어 인덱스가 정렬을 못 만든다 — V37 주석에 근거).
     *
     * <p><b>seek이 2단인 이유.</b> 평점은 DECIMAL(3,2)라 동점이 흔하고, 동점을 리뷰 수로 한 번 더
     * 가르므로 커서 조건도 {@code (r < cr) OR (r = cr AND (c < cc OR (c = cc AND id > cid)))}로
     * 세 겹이 된다. 안쪽 두 겹 중 하나라도 빠지면 <b>같은 평점·같은 리뷰 수 구간이 통째로 누락되거나
     * 중복된다</b> — 동점이 흔한 축이라 실제로 밟는 경로다.
     *
     * <p>커서 평점을 double로 바인딩하는 근거는 인기순과 같다 ({@link #findPopularRows} javadoc).
     * 리뷰 수는 INT라 double 왕복에서 값이 상하지 않는다.
     */
    @SuppressWarnings("unchecked")
    public List<RatingRow> findRatingRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Double cursorRating, Long cursorReviewCount, Long cursorPlaceId, int limit) {

        TagMasks masks = TagMasks.of(mainTagId, subTagAIds, subTagBIds);
        boolean useCursor =
                cursorRating != null && cursorReviewCount != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id, ps.avg_rating, ps.review_count, ps.bookmark_count
                FROM place_stats ps
                WHERE ps.town_id IN (:townIds)
                """);
        appendTagFilters(sql, masks);
        if (useCursor) {
            sql.append("""
                      AND (ps.avg_rating < :cursorRating
                           OR (ps.avg_rating = :cursorRating
                               AND (ps.review_count < :cursorReviewCount
                                    OR (ps.review_count = :cursorReviewCount
                                        AND ps.place_id > :cursorPlaceId))))
                    """);
        }
        sql.append(
                "ORDER BY ps.avg_rating DESC, ps.review_count DESC, ps.place_id ASC LIMIT :limitSize");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", townIds)
                .setParameter("limitSize", limit);
        bindTagFilters(query, masks);
        if (useCursor) {
            query.setParameter("cursorRating", cursorRating);
            query.setParameter("cursorReviewCount", cursorReviewCount);
            query.setParameter("cursorPlaceId", cursorPlaceId);
        }

        List<Object[]> rows = query.getResultList();
        List<RatingRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new RatingRow(
                    ((Number) row[0]).longValue(),
                    (BigDecimal) row[1],
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()));
        }
        return result;
    }

    /**
     * 리뷰 많은 순. {@code idx_place_stats_town_reviews}가 정렬을 만든다 (V36).
     * 평점순과 마찬가지로 술어가 하나도 없다 — {@code review_count}는 NOT NULL이고 0은
     * "리뷰가 0개"라는 정확한 사실이라 맨 뒤에 놓이면 그만이다.
     */
    public List<CountRow> findReviewCountRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Long cursorCount, Long cursorPlaceId, int limit) {
        return findCountRows("review_count", townIds, mainTagId, subTagAIds, subTagBIds,
                cursorCount, cursorPlaceId, limit);
    }

    /**
     * 북마크 많은 순. {@code idx_place_stats_town_bookmarks}가 정렬을 만든다 (V36).
     *
     * <p><b>인기순과 다른 정렬이다.</b> 인기 점수는 시간 감쇠와 평점을 섞은 복합 점수이고 이쪽은
     * 누적 원값이라, 같은 동네에서도 두 순서는 갈린다.
     */
    public List<CountRow> findBookmarkCountRows(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Long cursorCount, Long cursorPlaceId, int limit) {
        return findCountRows("bookmark_count", townIds, mainTagId, subTagAIds, subTagBIds,
                cursorCount, cursorPlaceId, limit);
    }

    /**
     * 카운트 축 두 정렬의 공용 본문. 정렬 컬럼 이름만 다르고 SELECT·필터·seek·타이브레이크가 전부
     * 같아, <b>한 문장을 공유해야</b> 두 정렬의 의미론이 구조적으로 붙어 있는다 — 복사해 두면
     * 한쪽만 고치는 실수가 조용히 통과한다 ({@code appendTagFilters}와 같은 이유).
     *
     * <p>{@code countColumn}은 호출부가 리터럴로만 넘기는 값이라 외부 입력이 닿지 않는다.
     * <b>이 메서드를 public으로 열지 말 것</b> — 그 순간 컬럼 이름이 입력이 되어 성질이 바뀐다.
     */
    @SuppressWarnings("unchecked")
    private List<CountRow> findCountRows(
            String countColumn,
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds,
            Long cursorCount, Long cursorPlaceId, int limit) {

        TagMasks masks = TagMasks.of(mainTagId, subTagAIds, subTagBIds);
        boolean useCursor = cursorCount != null && cursorPlaceId != null;

        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id, ps.bookmark_count, ps.review_count, ps.avg_rating
                FROM place_stats ps
                WHERE ps.town_id IN (:townIds)
                """);
        appendTagFilters(sql, masks);
        if (useCursor) {
            sql.append("  AND (ps.").append(countColumn).append(" < :cursorCount\n")
                    .append("       OR (ps.").append(countColumn)
                    .append(" = :cursorCount AND ps.place_id > :cursorPlaceId))\n");
        }
        sql.append("ORDER BY ps.").append(countColumn)
                .append(" DESC, ps.place_id ASC LIMIT :limitSize");

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", townIds)
                .setParameter("limitSize", limit);
        bindTagFilters(query, masks);
        if (useCursor) {
            query.setParameter("cursorCount", cursorCount);
            query.setParameter("cursorPlaceId", cursorPlaceId);
        }

        List<Object[]> rows = query.getResultList();
        List<CountRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new CountRow(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    (BigDecimal) row[3]));
        }
        return result;
    }

    /**
     * 거리순 <b>후보</b>. 정렬도 LIMIT도 없다 — 기준점이 요청마다 달라 인덱스가 만들 수 있는 순서가
     * 없으므로, 필터를 통과한 후보를 전량 실어 보내고 정렬은 앱({@code DistanceSort})이 맡는다.
     * 상한은 "시 단위 후보 ~1,800건"이고, 그 규모의 커버링 스캔을 수용한다는 것은 다중 town의
     * filesort를 수용한 근거와 같다.
     *
     * <p><b>이 경로만 {@code places}와 조인한다.</b> 조인의 방향은 place_stats → places의 PK
     * 룩업뿐이라 계획이 흔들릴 자유도가 없다 — 세미조인도 아니고 LIMIT 조기 종료도 없다.
     *
     * <p><b>좌표가 NULL인 장소는 여기서 걸러 낸다.</b> 거리를 잴 수 없는 장소를 "거리 무한대"로
     * 뒤에 붙이면 커서 seek이 NULL 비교에 걸려 페이지 경계에서 조용히 사라진다.
     *
     * <p>평점순은 같은 문제를 반대로 풀었다 — 저장을 NOT NULL 0으로 바꿔 NULL 자체를 없앴다(V37).
     * 여기서 그 수를 못 쓰는 것은 <b>대체할 값이 없기 때문</b>이다. 평점은 척도가 1~5라 0이
     * "리뷰 없음"과만 대응하지만, 위도·경도 0은 기니만의 실재 좌표라 "좌표 없음"과 구분되지 않는다.
     */
    @SuppressWarnings("unchecked")
    public List<DistanceCandidateRow> findDistanceCandidates(
            List<Long> townIds, Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds) {

        TagMasks masks = TagMasks.of(mainTagId, subTagAIds, subTagBIds);

        StringBuilder sql = new StringBuilder("""
                SELECT ps.place_id, p.latitude, p.longitude,
                       ps.bookmark_count, ps.review_count, ps.avg_rating
                FROM place_stats ps
                JOIN places p ON p.id = ps.place_id
                WHERE ps.town_id IN (:townIds)
                  AND p.latitude IS NOT NULL
                  AND p.longitude IS NOT NULL
                """);
        appendTagFilters(sql, masks);

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("townIds", townIds);
        bindTagFilters(query, masks);

        List<Object[]> rows = query.getResultList();
        List<DistanceCandidateRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new DistanceCandidateRow(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).doubleValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    (BigDecimal) row[5]));
        }
        return result;
    }

    /**
     * 요청의 태그 조건을 그룹별 마스크 <b>셋</b>으로 옮긴 것. 0은 "이 그룹의 술어를 붙이지 않는다".
     *
     * <p>세 그룹은 AND로 엮이므로 <b>한 마스크로 합치면 안 된다</b> — 합치는 순간 OR가 되어 의미가
     * 뒤집힌다. 그룹 안의 OR만 마스크가 흡수한다 ({@code TagBitmask} 참조).
     *
     * <p>메인 태그가 없으면 서브 조건은 통째로 버린다. 북마크 검색의 {@code PlaceTagMatcher}가
     * {@code mainTagId == null}이면 원본을 그대로 돌려주는 것과 같은 규칙이고, 두 경로가 여기서
     * 갈리면 같은 요청이 경로마다 다른 답을 낸다.
     */
    private record TagMasks(long main, long subA, long subB) {

        static TagMasks of(Long mainTagId, List<Long> subTagAIds, List<Long> subTagBIds) {
            if (mainTagId == null) {
                return new TagMasks(0L, 0L, 0L);
            }
            return new TagMasks(
                    TagBitmask.of(mainTagId),
                    TagBitmask.ofAny(subTagAIds),
                    TagBitmask.ofAny(subTagBIds));
        }
    }

    /**
     * 태그 술어. 두 정렬이 같은 문자열을 <b>공유</b>해야 "정렬 축만 다르고 필터 의미론은 같다"가
     * 구조적으로 보장된다 — 복사해 두면 한쪽만 고치는 실수가 조용히 통과한다.
     *
     * <p>마스크가 0인 그룹은 술어를 붙이지 않는다. 태그 조건이 아예 없는 요청의 SQL이 태그 도입
     * 전과 <b>바이트째 같아지는</b> 것이 그 결과이고, 그것이 최다 트래픽 경로다.
     *
     * <p><b>{@code != 0}을 {@code = :mask}로 바꾸지 말 것.</b> 등호는 "그 그룹의 태그를 전부 가진
     * 장소"가 되어 그룹 안 OR가 AND로 뒤집힌다.
     *
     * <p><b>⚠️ 여기서 {@code tags}를 조인하지 말 것.</b> 요청에 실린 태그 id의 존재·활성·타입은
     * 상위 {@code TagValidator.validatePlaceTagConditions}가 이미 검증해 400/404로 막는다
     * ({@code PlaceService#getPlaces}). 조인은 정보를 보태지 않으면서 이 쿼리에 유일하게 남은
     * "조인 없음"이라는 성질을 깨뜨린다.
     *
     * <p><b>북마크 검색 경로와의 차이 — 타입·활성을 여기서는 검사하지 않는다.</b>
     * {@code PlaceTagMatcher}는 엔티티의 {@code Tag}를 직접 보고 타입과 활성을 확인한다. 따라서
     * <b>타입이 어긋나거나 비활성인 태그 id</b>가 오면 북마크 검색은 0건, 목록은 매칭이 되어 두 경로가
     * 갈린다. 그 갈림은 <b>상위 검증이 통과시키지 않는 입력에서만</b> 관측된다.
     */
    private void appendTagFilters(StringBuilder sql, TagMasks masks) {
        if (masks.main() != 0L) {
            sql.append("  AND (ps.tag_bitmask & :mainMask) != 0\n");
        }
        if (masks.subA() != 0L) {
            sql.append("  AND (ps.tag_bitmask & :subAMask) != 0\n");
        }
        if (masks.subB() != 0L) {
            sql.append("  AND (ps.tag_bitmask & :subBMask) != 0\n");
        }
    }

    private void bindTagFilters(Query query, TagMasks masks) {
        if (masks.main() != 0L) {
            query.setParameter("mainMask", masks.main());
        }
        if (masks.subA() != 0L) {
            query.setParameter("subAMask", masks.subA());
        }
        if (masks.subB() != 0L) {
            query.setParameter("subBMask", masks.subB());
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
