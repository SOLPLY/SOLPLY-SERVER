package org.sopt.solply_server.domain.place.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceStatsView;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.sopt.solply_server.domain.place.entity.PlaceStatsId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceStatsRepository extends JpaRepository<PlaceStats, PlaceStatsId> {

    /**
     * 한 회차의 점수·카운트를 원본에서 재계산해 <b>새 버전의 행 집합</b>으로 적재한다.
     *
     * <pre>
     * score = W_B × ln(1 + Σ북마크[0.5^(경과일 / 반감기)])
     *       + W_R × (조정평점 − C)
     * 조정평점 = (Σ평점 + m × C) / (리뷰 수 + m),   C = 전체 평균 평점
     * </pre>
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
     * <p><b>{@code WHERE p.active = 1}이 조회의 불변식을 만든다</b> — 비활성 장소는 새 버전에 아예
     * 들어가지 않으므로 인기순 쿼리가 places를 되짚지 않아도 된다. 옛 버전에 남은 잔행은
     * {@link #deleteVersionsOtherThan}이 버전째 지운다.
     *
     * <p><b>{@code ON DUPLICATE KEY UPDATE}는 멱등성 전용이다.</b> 새 버전 행이라 평시엔 순수
     * INSERT지만, 같은 {@code calculatedAt} 재실행이 PK 충돌 대신 동일값 덮어쓰기가 되게 한다.
     * 버전 행에는 prev 시프트가 없으므로 <b>대입 순서 계약도 없다</b>.
     *
     * <p><b>⚠️ 반드시 {@code READ_COMMITTED}에서 호출할 것.</b> {@code INSERT ... SELECT}는
     * REPEATABLE READ에서 두 소스 테이블 <em>전체</em>에 shared next-key 락을 걸어 동시
     * 북마크·리뷰 INSERT를 {@code ERROR 1205}로 죽인다(벤치 실측 1,063만 건 대 0건). RC에서도
     * FK 부모 검사로 {@code places}에는 갱신 행 수만큼 S 락이 커밋까지 남아, 어드민의 동네 일괄
     * 비활성화가 배치 시간만큼 대기한다 — RC에서 유일하게 남는 차단이다.
     * 전제: {@code binlog_format = ROW}. STATEMENT/MIXED면 이 문장 자체가 {@code ERROR 1665}로 거부된다.
     *
     * <p><b>⚠️ NULL이 조용히 번지는 자리가 셋 있다. 아래 세 장치를 지우지 말 것.</b>
     * <ol>
     *   <li><b>리뷰 서브쿼리가 {@code AVG}와 별도로 {@code SUM(rating)}을 뽑는다.</b>
     *       조정 평점의 분자를 {@code cnt × avg_rating}으로 대신하면 리뷰 0건 장소에서
     *       {@code 0 × NULL = NULL}이 되어 그 장소의 {@code popular_score}가 NULL이 되고,
     *       {@code NOT NULL} 컬럼이라 배치 전체가 터진다.</li>
     *   <li><b>{@code COALESCE(AVG(pr.rating), 3.0)}.</b> 리뷰 테이블이 통째로 비면 {@code C}가
     *       NULL이라 <em>전</em> 장소의 점수가 NULL이 된다. 서비스 초기에 실재하는 상태다.
     *       폴백 값이 무엇이든 결과는 같다 — 리뷰가 하나도 없으면 모든 장소가 조정평점 = C가 되어
     *       {@code C}가 상쇠된다. 그래서 프로퍼티로 빼지 않고 리터럴로 둔다.</li>
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
     * <p>인덱스 전제: 북마크 축은 {@code idx_bookmark_target}(V23), 리뷰 축은
     * {@code idx_place_reviews_place_created_rating}(V22)로 각각 인덱스 전용 스캔이어야 한다.
     * {@code C}를 구하는 {@code CROSS JOIN}은 상관 서브쿼리가 아니라 MySQL이 한 번만 평가한다.
     * 배치가 갑자기 느려지면 EXPLAIN에서 이 둘이 빠지지 않았는지부터 볼 것.
     *
     * <p>{@code VALUES(col)}은 deprecated라 실행마다 {@code Warning 1287}이 참조 수만큼 뜬다.
     * {@code INSERT ... SELECT}에서는 행 별칭 문법이 {@code ERROR 1054}로 깨져 쓸 수 없고,
     * 대안은 SELECT 전체를 파생 테이블로 감싸는 형태뿐이다 — MySQL 8.4 이상으로 올려 함수가
     * 실제로 제거될 때 그 형태로 교체할 것.
     *
     * <p>상세: {@code docs/design/2026-08-05-place-stats-version-rows.md} §3
     *
     * @param version        이번 회차의 버전 = {@code calculatedAt}의 epoch 초.
     *                       {@code PlaceStatsMetaRepository#toVersion}이 정하는 규약을 따라야 한다
     * @param calculatedAt   감쇠 기준 시각이자 <b>집계 대상의 상한</b>
     * @param bookmarkWeight 로그 압축된 북마크 축의 배율. 로그 <b>바깥</b>에 곱해진다
     * @param reviewWeight   리뷰 축의 배율. 실제 기여는 (조정평점 − C)가 곱해진 유계 값이다
     * @param halfLifeDays   북마크 감쇠 반감기(일). <b>0이면 북마크 항이 조용히 통째로 0이 되고
     *                       음수면 증폭이 된다</b> — {@code PlaceStatsProperties}의 {@code @Positive}가 막는다
     * @param minReviewCount 베이지안 사전 표본 수 {@code m}. <b>0이면 베이지안 보정이 통째로 사라지고
     *                       리뷰 0건 장소가 {@code 0/0}으로 NULL이 된다</b> — 같은 {@code @Positive}가 막는다
     * @return 영향 행 수 (MySQL은 INSERT를 1, UPDATE를 2로 센다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO place_stats (
            place_id, version, town_id, popular_score,
            bookmark_count, review_count, avg_rating)
        SELECT p.id,
               :version,
               p.town_id,
               :bookmarkWeight * LN(1 + COALESCE(b.decayed, 0))
                   + :reviewWeight * (
                       (COALESCE(r.sum_rating, 0) + :minReviewCount * g.c)
                           / (COALESCE(r.cnt, 0) + :minReviewCount) - g.c),
               COALESCE(b.cnt, 0),
               COALESCE(r.cnt, 0),
               r.avg_rating
        FROM places p
        CROSS JOIN (
            SELECT COALESCE(AVG(pr.rating), 3.0) + 0e0 AS c
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
        ) g
        LEFT JOIN (
            SELECT bm.target_id AS place_id,
                   COUNT(*) AS cnt,
                   SUM(POW(0.5,
                       TIMESTAMPDIFF(SECOND, bm.created_at, :calculatedAt)
                           / 86400.0 / :halfLifeDays)) AS decayed
            FROM bookmarks bm
            WHERE bm.target_type = 'PLACE'
              AND bm.created_at <= :calculatedAt
            GROUP BY bm.target_id
        ) b ON b.place_id = p.id
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   AVG(pr.rating) AS avg_rating,
                   SUM(pr.rating) AS sum_rating
            FROM place_reviews pr
            WHERE pr.created_at <= :calculatedAt
            GROUP BY pr.place_id
        ) r ON r.place_id = p.id
        WHERE p.active = 1
        ON DUPLICATE KEY UPDATE
            town_id        = VALUES(town_id),
            popular_score  = VALUES(popular_score),
            bookmark_count = VALUES(bookmark_count),
            review_count   = VALUES(review_count),
            avg_rating     = VALUES(avg_rating)
        """, nativeQuery = true)
    int upsertAll(
            @Param("version") long version,
            @Param("calculatedAt") LocalDateTime calculatedAt,
            @Param("bookmarkWeight") double bookmarkWeight,
            @Param("reviewWeight") double reviewWeight,
            @Param("halfLifeDays") double halfLifeDays,
            @Param("minReviewCount") int minReviewCount);

    /**
     * 보관 대상(현·직전) 밖의 버전을 통째로 지운다. <b>비활성 장소의 잔행 청소를 겸한다</b> —
     * 옛 버전이 통째로 죽으면서 함께 사라지므로 비활성 장소를 골라 지우는 삭제가 따로 필요 없다.
     *
     * <p><b>{@code prev}에 NULL을 넘기지 말 것.</b> {@code x NOT IN (a, NULL)}은 참이 되지 못해
     * 아무것도 지워지지 않는다. 직전 버전이 없으면 {@code current}를 두 번 넘긴다
     * ({@code PlaceStatsBatchProcessor}가 그렇게 부른다).
     *
     * <p>상세: {@code docs/design/2026-08-05-place-stats-version-rows.md} §3
     *
     * @return 지운 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        DELETE FROM place_stats
         WHERE version NOT IN (:current, :prev)
        """, nativeQuery = true)
    int deleteVersionsOtherThan(@Param("current") long current, @Param("prev") long prev);

    /**
     * 북마크 검색 경로의 점수·카운트 조회. PK 프리픽스 IN 조회라 후보 수(시 단위 병합 최대 ~1,800)에
     * 선형이고 북마크 수와는 무관하다.
     *
     * <p><b>현 버전을 메타에서 따로 읽지 않고 스칼라 서브쿼리로 접합하는 이유:</b> 따로 읽으면 이
     * 경로의 요청당 SQL이 1개 는다. PK 1행 조회라 비용은 상수이고 MySQL이 상수로 한 번만 평가한다.
     *
     * <p>JPQL이 아닌 것은 {@code place_stats_meta}가 엔티티가 아니라 배치가 미는 버전 값 1행이라
     * JPQL 서브쿼리의 대상이 될 수 없어서다. 그래서 매핑도 여기서 직접 한다.
     */
    default List<PlaceStatsView> findViewsByPlaceIds(List<Long> placeIds) {
        return findCurrentVersionViewRows(placeIds).stream()
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
          AND ps.version = (SELECT current_generation FROM place_stats_meta WHERE id = 1)
        """, nativeQuery = true)
    List<Object[]> findCurrentVersionViewRows(@Param("placeIds") List<Long> placeIds);
}
