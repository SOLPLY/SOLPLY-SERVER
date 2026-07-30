package org.sopt.solply_server.domain.place.repository;

import java.time.LocalDateTime;
import org.sopt.solply_server.domain.place.entity.PlaceStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceStatsRepository extends JpaRepository<PlaceStats, Long> {

    /**
     * 전체 장소의 인기순 복합 점수를 원본에서 재계산해 UPSERT한다.
     *
     * <pre>
     * score = Σ북마크[W_B × 0.5^(경과일 / 반감기)] + Σ리뷰[W_R × (rating − 3) × 0.5^(경과일 / 반감기)]
     * </pre>
     *
     * <p><b>멱등성:</b> 감쇠 기준 시각을 파라미터로 받는다. 같은 {@code calculatedAt}으로
     * 몇 번을 실행하든 결과가 동일하다. NOW()를 쓰면 실행마다 값이 미세하게 달라져
     * "멱등"이라는 성질 자체를 검증할 수 없게 된다.
     *
     * <p><b>증분이 아니라 전량 재계산인 이유:</b> 이벤트 유실·중복 컨슈밍·배포 중 재시작 누락이
     * 전부 다음 1회로 씻긴다. drift가 원리적으로 불가능하므로 별도의 정합성 보정 배치가
     * 필요 없고, 다중 인스턴스가 동시에 돌려도 결과가 같아 리더 선출도 필요 없다.
     *
     * <p><b>평점을 3점 중심화하는 이유:</b> 합(Σ) 형태라 원값을 쓰면 1점 리뷰도 점수를 올려
     * 평점 낮은 장소가 리뷰 수만으로 상위에 오른다. (rating − 3)이면 나쁜 평가가 순위를 끌어내린다.
     *
     * <p><b>인덱스 전제:</b> 북마크 축은 V23의 idx_bookmark_target (target_type, target_id,
     * created_at), 리뷰 축은 V22의 idx_place_reviews_place_created_rating으로 각각
     * 인덱스 전용 스캔이 되어야 한다. 배치가 갑자기 느려지면 EXPLAIN에서 이 두 인덱스가
     * 빠지지 않았는지부터 확인할 것.
     *
     * <p><b>{@code VALUES(col)} 문법 — deprecated이며 경고가 실제로 뜬다.</b>
     * mysql:8.0(8.0.41) 실측 기준 이 문장을 실행할 때마다 경고가 하나 올라온다:
     * {@code Warning 1287: 'VALUES function' is deprecated and will be removed in a future release}.
     * 동작에는 영향이 없고(값은 정상 갱신된다) JDBC {@code SQLWarning}으로만 전달되지만,
     * 경고 로깅을 켜면 배치 1회당 1건이 남는다.
     *
     * <p>그럼에도 대체 문법을 쓰지 않은 이유는 <b>행 별칭이 {@code INSERT ... SELECT}에서 아예 안 되기
     * 때문</b>이다. {@code SELECT ... FROM s AS new ... ON DUPLICATE KEY UPDATE v = new.v}는 별칭이
     * 소스 테이블 별칭으로 해석돼 {@code ERROR 1054 Unknown column 'new.v'}로 깨진다(실측).
     * 동작하는 대안은 SELECT 전체를 파생 테이블로 감싸는 형태
     * ({@code INSERT ... SELECT * FROM (SELECT ...) AS nw ON DUPLICATE KEY UPDATE col = nw.col})
     * 하나뿐이고, 8.0.41에서 경고 없이 동작함을 확인했다. 다만 집계 서브쿼리를 한 겹 더 감싸는
     * 구조 변경이라 지금은 채택하지 않는다 — MySQL을 8.4 이상으로 올려 이 함수가 실제로 제거될 때
     * 이 형태로 교체할 것.
     *
     * @param calculatedAt   감쇠 기준 시각. 호출자가 정해 넘기므로 같은 값이면 결과가 같다
     * @return 영향받은 행 수 (MySQL은 INSERT를 1, UPDATE를 2로 세므로 장소 수와 일치하지 않는다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO place_stats (
            place_id, town_id, active, popular_score,
            bookmark_count, review_count, avg_rating, calculated_at)
        SELECT p.id,
               p.town_id,
               p.active,
               COALESCE(b.score, 0) + COALESCE(r.score, 0),
               COALESCE(b.cnt, 0),
               COALESCE(r.cnt, 0),
               r.avg_rating,
               :calculatedAt
        FROM places p
        LEFT JOIN (
            SELECT bm.target_id AS place_id,
                   COUNT(*) AS cnt,
                   SUM(:bookmarkWeight * POW(0.5,
                       TIMESTAMPDIFF(SECOND, bm.created_at, :calculatedAt)
                           / 86400.0 / :halfLifeDays)) AS score
            FROM bookmarks bm
            WHERE bm.target_type = 'PLACE'
            GROUP BY bm.target_id
        ) b ON b.place_id = p.id
        LEFT JOIN (
            SELECT pr.place_id AS place_id,
                   COUNT(*) AS cnt,
                   AVG(pr.rating) AS avg_rating,
                   SUM(:reviewWeight * (pr.rating - 3) * POW(0.5,
                       TIMESTAMPDIFF(SECOND, pr.created_at, :calculatedAt)
                           / 86400.0 / :halfLifeDays)) AS score
            FROM place_reviews pr
            GROUP BY pr.place_id
        ) r ON r.place_id = p.id
        ON DUPLICATE KEY UPDATE
            town_id        = VALUES(town_id),
            active         = VALUES(active),
            popular_score  = VALUES(popular_score),
            bookmark_count = VALUES(bookmark_count),
            review_count   = VALUES(review_count),
            avg_rating     = VALUES(avg_rating),
            calculated_at  = VALUES(calculated_at)
        """, nativeQuery = true)
    int upsertAll(
            @Param("calculatedAt") LocalDateTime calculatedAt,
            @Param("bookmarkWeight") double bookmarkWeight,
            @Param("reviewWeight") double reviewWeight,
            @Param("halfLifeDays") double halfLifeDays);
}
