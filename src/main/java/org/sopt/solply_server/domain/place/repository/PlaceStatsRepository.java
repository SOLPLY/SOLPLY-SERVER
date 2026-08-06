package org.sopt.solply_server.domain.place.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code place_stats}의 쓰기·읽기 문장 모음. 장소당 행 하나이고 주인이 둘이다 —
 * 컬럼별 소유 배치는 {@link PlaceStats} javadoc의 표에 있다.
 *
 * <p><b>두 UPSERT/UPDATE의 대입 목록이 서로 겹치면 안 된다.</b> 겹치는 순간 늦게 도는 배치가
 * 상대의 신선한 값을 자기 회차의 낡은 값으로 되돌린다. 이 계약은 SQL의 {@code SET}·
 * {@code ON DUPLICATE KEY UPDATE} 목록에만 존재하므로 컬럼을 더할 때 반드시 어느 쪽 소유인지
 * 먼저 정할 것 ({@code PlaceStatsBatchProcessorIT}의 소유권 테스트 두 건이 감시한다).
 */
public interface PlaceStatsRepository extends JpaRepository<PlaceStats, Long> {

    /**
     * 표시용 카운트를 원본에서 재계산해 활성 장소 전량에 적재한다. <b>행의 존재 자체를 정하는
     * 문장이자 카운트 배치의 1단계</b>이며, {@code popular_score}·{@code score_calculated_at}은
     * 건드리지 않는다.
     *
     * <p><b>{@code WHERE p.active = 1}이 조회의 불변식을 만든다</b> — 비활성 장소는 이번 회차에
     * 아예 들어가지 않으므로 인기순 쿼리가 places를 되짚지 않아도 된다. 다만 이 문장은 "새로
     * 넣지 않을" 뿐 이미 있는 행을 지우지 않는다. 짝이 되는 삭제가
     * {@link #deleteStaleRows}이고, <b>둘은 반드시 한 트랜잭션에</b> 있어야 한다.
     *
     * <p><b>{@code created_at <= :calculatedAt} 상한을 지우지 말 것.</b> 잃는 것은 성능이 아니라
     * 멱등성이라는 문장의 참/거짓이다 — 같은 기준 시각으로 다시 돌렸을 때 그 사이 들어온 활동이
     * 결과를 바꾼다면 "회차를 재실행해도 안전하다"가 성립하지 않는다.
     *
     * <p><b>{@code ON DUPLICATE KEY UPDATE}는 평시 경로다.</b> 장소당 행이 하나뿐이라 두 번째
     * 회차부터는 전부 UPDATE로 흐른다. 여기 나열된 다섯 컬럼이 카운트 배치의 소유 목록 전부이며,
     * {@code popular_score}가 여기 끼면 매시 배치가 새벽에 계산한 점수를 0으로 되돌린다.
     *
     * <p><b>⚠️ 반드시 {@code READ_COMMITTED}에서 호출할 것.</b> {@code INSERT ... SELECT}는
     * REPEATABLE READ에서 두 소스 테이블 <em>전체</em>에 shared next-key 락을 걸어 동시
     * 북마크·리뷰 INSERT를 {@code ERROR 1205}로 죽인다(벤치 실측 1,063만 건 대 0건). RC에서도
     * FK 부모 검사로 {@code places}에는 갱신 행 수만큼 S 락이 커밋까지 남아, 어드민의 동네 일괄
     * 비활성화가 배치 시간만큼 대기한다 — RC에서 유일하게 남는 차단이다.
     * 전제: {@code binlog_format = ROW}. STATEMENT/MIXED면 이 문장 자체가 {@code ERROR 1665}로 거부된다.
     *
     * <p><b>{@code avg_rating}에 COALESCE를 걸지 않는다.</b> 리뷰가 없으면 NULL이 정확한 답이고,
     * 0으로 채우는 순간 "평점 0점"으로 읽힌다. 카운트 둘은 반대로 0이 정답이라 COALESCE를 건다.
     *
     * <p>{@code VALUES(col)}은 deprecated라 실행마다 {@code Warning 1287}이 참조 수만큼 뜬다.
     * {@code INSERT ... SELECT}에서는 행 별칭 문법이 {@code ERROR 1054}로 깨져 쓸 수 없고,
     * 대안은 SELECT 전체를 파생 테이블로 감싸는 형태뿐이다 — MySQL 8.4 이상으로 올려 함수가
     * 실제로 제거될 때 그 형태로 교체할 것.
     *
     * <p>인덱스 전제: 북마크 축은 {@code idx_bookmark_target}(V23), 리뷰 축은
     * {@code idx_place_reviews_place_created_rating}(V22)로 각각 인덱스 전용 스캔이어야 한다.
     *
     * @param calculatedAt 이번 회차의 기준 시각이자 <b>집계 대상의 상한</b>. 모든 행의
     *                     {@code count_calculated_at}이 이 값이 되며, 그것이 곧 잔행 판정의 기준이다
     * @return 영향 행 수 (MySQL은 INSERT를 1, UPDATE를 2로 센다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO place_stats (
            place_id, town_id, bookmark_count, review_count, avg_rating, count_calculated_at)
        SELECT p.id,
               p.town_id,
               COALESCE(b.cnt, 0),
               COALESCE(r.cnt, 0),
               r.avg_rating,
               :calculatedAt
        FROM places p
        LEFT JOIN (
            SELECT bm.target_id AS place_id,
                   COUNT(*) AS cnt
            FROM bookmarks bm
            WHERE bm.target_type = 'PLACE'
              AND bm.created_at <= :calculatedAt
            GROUP BY bm.target_id
        ) b ON b.place_id = p.id
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   AVG(pr.rating) AS avg_rating
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
            GROUP BY pr.place_id
        ) r ON r.place_id = p.id
        WHERE p.active = 1
        ON DUPLICATE KEY UPDATE
            town_id             = VALUES(town_id),
            bookmark_count      = VALUES(bookmark_count),
            review_count        = VALUES(review_count),
            avg_rating          = VALUES(avg_rating),
            count_calculated_at = VALUES(count_calculated_at)
        """, nativeQuery = true)
    int upsertCounts(@Param("calculatedAt") LocalDateTime calculatedAt);

    /**
     * 이번 회차가 건드리지 않은 행을 지운다 = <b>비활성화·삭제된 장소의 잔행 청소</b>.
     * 카운트 배치의 2단계이며 {@link #upsertCounts}와 <b>같은 트랜잭션</b>이어야 한다.
     *
     * <p>갈라지면 두 문장 사이에 "활성 장소는 새 회차 값, 비활성 장소는 옛 회차 값"이 공존하는
     * 구간이 생기고, 적재만 커밋된 채 삭제가 죽으면 내려간 장소가 다음 회차까지 인기순에 남는다.
     *
     * <p>V29까지 이 책임은 {@code deleteVersionsOtherThan}이 겸업했다 — 옛 버전이 통째로 죽으면서
     * 비활성 장소의 행도 함께 사라졌다. 버전이 없어진 지금은 이 문장이 유일한 청소 경로다.
     * <b>지우면 어드민이 내린 장소가 인기순에 영구히 남는다</b>(최대 1시간이 아니라 영구다).
     *
     * <p>{@code count_calculated_at}이 {@code NOT NULL}이라 {@code <>} 비교에 NULL 함정이 없다.
     *
     * @param calculatedAt 방금 {@link #upsertCounts}에 넘긴 것과 <b>같은 값</b>이어야 한다.
     *                     다르면 이번 회차가 방금 적재한 행까지 전부 지운다
     * @return 지운 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        DELETE FROM place_stats
         WHERE count_calculated_at <> :calculatedAt
        """, nativeQuery = true)
    int deleteStaleRows(@Param("calculatedAt") LocalDateTime calculatedAt);

    /**
     * 인기 점수를 원본에서 재계산해 <b>이미 존재하는 행에만</b> 덮어쓴다.
     *
     * <pre>
     * score = W_B × ln(1 + Σ북마크[0.5^(경과일 / 반감기)])
     *       + W_R × (조정평점 − C)
     * 조정평점 = (Σ평점 + m × C) / (리뷰 수 + m),   C = 전체 평균 평점
     * </pre>
     *
     * <p><b>INSERT가 아니라 UPDATE인 것이 소유권 계약이다.</b> 행을 만드는 주체는 카운트 배치
     * 하나뿐이고, 이 문장은 그 행의 점수 두 칸만 정한다. INSERT로 바꾸면 {@code town_id}·
     * {@code count_calculated_at}에 값을 지어내야 하고, 그 순간 잔행 판정이 무너진다.
     * 아직 카운트 배치가 닿지 않은 신규 장소는 여기서도 대상이 아니다 — 그 장소는 다음 카운트
     * 배치가 0점 행으로 만들고, 그 다음 새벽에 점수를 받는다.
     *
     * <p><b>감쇠가 북마크에만 걸리는 것은 의도적 비대칭이다</b> — 인기(북마크)는 최근 활동이라
     * 감쇠하고, 평판(리뷰)은 시점 무관한 누적 판단이라 1건이 1표씩 들어간다.
     *
     * <p><b>북마크 축에 로그를 씌우는 이유는 두 축의 치역이 다르기 때문이다.</b> 북마크 합은 건수에
     * 비례해 수만까지 가는데 리뷰 항은 평점 범위에 갇혀 있어, 그냥 더하면 리뷰가 순위에 아무
     * 영향을 못 준다. 로그가 자릿수를 맞춘다. {@code W_B}가 로그 <em>바깥</em>인 것도 계약이다
     * ({@code PlaceStatsProperties#bookmarkWeight} 참조).
     *
     * <p><b>리뷰 축이 평균의 편차인 것은 왜곡 방지다.</b> 합 형태였을 때는 리뷰 수가 많을수록
     * 값이 무한정 커져 평점의 <em>높낮이</em>가 아니라 <em>개수</em>가 순위를 정했다. 베이지안
     * 평균은 표본이 적은 장소를 전체 평균 쪽으로 당겨, 리뷰 1건짜리 5점이 100건짜리 4.5점을
     * 이기지 못하게 한다. {@code C}를 빼서 중심화하므로 <b>리뷰가 없는 장소의 기여는 정확히 0</b>이고,
     * 전체 평균이 중앙(3.0)보다 위인 한 저평점의 페널티가 고평점의 보상보다 크다.
     *
     * <p><b>{@code created_at <= :calculatedAt} 상한을 지우지 말 것.</b> 잃는 것은 성능이 아니라
     * 멱등성이라는 문장의 참/거짓이다. 게다가 상한이 없으면 미래 시각 활동에서
     * {@code POW(0.5, 음수) > 1}이 되어 북마크 축이 감쇠가 아니라 증폭이 된다.
     *
     * <p><b>표시용 {@code review_count}·{@code avg_rating}을 재활용하지 않고 리뷰를 다시 훑는
     * 이유.</b> 두 값의 신선도가 이 배치와 다르다(카운트는 ≤1h, 점수는 ≤24h). 재활용하면 점수가
     * "한 시간 전 카운트 + 지금 계산한 감쇠"라는 섞인 시점 위에 서고, 무엇보다
     * {@code cnt × avg_rating}으로 조정 평점의 분자를 대신하는 순간 리뷰 0건 장소에서
     * {@code 0 × NULL = NULL}이 되어 {@code NOT NULL} 컬럼에 걸려 배치 전체가 터진다.
     * 그래서 여기서도 {@code SUM(rating)}을 따로 뽑는다.
     *
     * <p><b>⚠️ NULL이 조용히 번지는 자리가 둘 더 있다. 아래 두 장치를 지우지 말 것.</b>
     * <ol>
     *   <li><b>{@code COALESCE(AVG(pr.rating), 3.0)}.</b> 리뷰 테이블이 통째로 비면 {@code C}가
     *       NULL이라 <em>전</em> 장소의 점수가 NULL이 된다. 서비스 초기에 실재하는 상태다.
     *       폴백 값이 무엇이든 결과는 같다 — 리뷰가 하나도 없으면 모든 장소가 조정평점 = C가 되어
     *       {@code C}가 상쇄된다. 그래서 프로퍼티로 빼지 않고 리터럴로 둔다.</li>
     *   <li><b>{@code + 0e0}이 {@code C}를 DOUBLE로 승격시킨다.</b> {@code AVG}는 DECIMAL을
     *       돌려주는데, 그대로 두면 조정 평점의 나눗셈이 DECIMAL 산술로 계산돼 스케일이
     *       {@code div_precision_increment}(기본 4)에 잘린다. 감쇠항은 DOUBLE이라 두 축의
     *       정밀도가 어긋나고, 그 차이는 동점 근처의 순위로만 드러나 추적이 어렵다.</li>
     * </ol>
     *
     * <p>{@code LN}의 정의역은 안전하다 — 감쇠 합이 0 이상이라 {@code LN(1 + Σ) ≥ LN(1) = 0}이고
     * 로그가 발산할 입구가 없다. 다만 그것은 {@code 1 +}가 있을 때만 참이다.
     *
     * <p><b>{@code C}는 전 장소가 공유하는 값이라 한 장소의 리뷰가 다른 장소의 점수를 움직인다.</b>
     * 이 결합은 공식의 성질이지 버그가 아니지만, 테스트 픽스처를 짤 때 반드시 의식해야 한다 —
     * 리뷰를 한 장소에만 넣으면 {@code C}가 그 장소의 평균과 같아져 기여가 항상 0이 된다.
     *
     * <p><b>⚠️ 이 문장도 {@code READ_COMMITTED}에서 호출할 것.</b> 소스 테이블 스캔의 락 성질은
     * {@link #upsertCounts}와 같다. 다만 여기서는 갱신 대상이 {@code place_stats} 전 행이라
     * 그 X 락이 커밋까지 남는다 — 그래서 두 배치가 겹쳐 돌지 않게 시각을 갈라 뒀다
     * ({@code PlaceStatsFacade} 참조).
     *
     * @param calculatedAt   감쇠 기준 시각이자 <b>집계 대상의 상한</b>
     * @param bookmarkWeight 로그 압축된 북마크 축의 배율. 로그 <b>바깥</b>에 곱해진다
     * @param reviewWeight   리뷰 축의 배율. 실제 기여는 (조정평점 − C)가 곱해진 유계 값이다
     * @param halfLifeDays   북마크 감쇠 반감기(일). <b>0이면 북마크 항이 조용히 통째로 0이 되고
     *                       음수면 증폭이 된다</b> — {@code PlaceStatsProperties}의 {@code @Positive}가 막는다
     * @param minReviewCount 베이지안 사전 표본 수 {@code m}. <b>0이면 베이지안 보정이 통째로 사라지고
     *                       리뷰 0건 장소가 {@code 0/0}으로 NULL이 된다</b> — 같은 {@code @Positive}가 막는다
     * @return 갱신된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE place_stats ps
        CROSS JOIN (
            SELECT COALESCE(AVG(pr.rating), 3.0) + 0e0 AS c
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
        ) g
        LEFT JOIN (
            SELECT bm.target_id AS place_id,
                   SUM(POW(0.5,
                       TIMESTAMPDIFF(SECOND, bm.created_at, :calculatedAt)
                           / 86400.0 / :halfLifeDays)) AS decayed
            FROM bookmarks bm
            WHERE bm.target_type = 'PLACE'
              AND bm.created_at <= :calculatedAt
            GROUP BY bm.target_id
        ) b ON b.place_id = ps.place_id
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   SUM(pr.rating) AS sum_rating
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
            GROUP BY pr.place_id
        ) r ON r.place_id = ps.place_id
        SET ps.popular_score = :bookmarkWeight * LN(1 + COALESCE(b.decayed, 0))
                                   + :reviewWeight * (
                                       (COALESCE(r.sum_rating, 0) + :minReviewCount * g.c)
                                           / (COALESCE(r.cnt, 0) + :minReviewCount) - g.c),
            ps.score_calculated_at = :calculatedAt
        """, nativeQuery = true)
    int updateScores(
            @Param("calculatedAt") LocalDateTime calculatedAt,
            @Param("bookmarkWeight") double bookmarkWeight,
            @Param("reviewWeight") double reviewWeight,
            @Param("halfLifeDays") double halfLifeDays,
            @Param("minReviewCount") int minReviewCount);

    /**
     * 점수를 한 번이라도 받은 행이 있는가. 기동 시 점수 백필 여부를 정하는 유일한 판정이다.
     *
     * <p>{@code count() > 0}으로는 이 질문에 답할 수 없다 — 카운트 배치가 만든 0점·미채점 행도
     * 행이기 때문이다. V32 직후처럼 테이블을 재생성한 배포에서 그 구분이 없으면 다음 새벽까지
     * 전 장소가 0점으로 서빙된다.
     */
    boolean existsByScoreCalculatedAtNotNull();

    /**
     * 북마크 검색 경로의 점수·카운트 조회. PK IN 조회라 후보 수(시 단위 병합 최대 ~1,800)에
     * 선형이고 북마크 수와는 무관하다.
     *
     * <p>JPQL이 아닌 것은 아래 원시 행 조회와 짝을 맞추기 위해서다 — 매핑도 여기서 직접 한다.
     */
    default List<PlaceStatsView> findViewsByPlaceIds(List<Long> placeIds) {
        return findViewRows(placeIds).stream()
                .map(row -> new PlaceStatsView(
                        ((Number) row[0]).longValue(),
                        (BigDecimal) row[1],
                        ((Number) row[2]).intValue(),
                        ((Number) row[3]).intValue(),
                        (BigDecimal) row[4]))
                .toList();
    }

    /** {@link #findViewsByPlaceIds}의 원시 행. 직접 부르지 말 것 — 매핑은 그쪽이 맡는다. */
    @Query(value = """
        SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
               ps.review_count, ps.avg_rating
        FROM place_stats ps
        WHERE ps.place_id IN (:placeIds)
        """, nativeQuery = true)
    List<Object[]> findViewRows(@Param("placeIds") List<Long> placeIds);
}
