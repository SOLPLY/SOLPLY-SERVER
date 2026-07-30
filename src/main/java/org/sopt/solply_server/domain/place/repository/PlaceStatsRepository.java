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
     * <p><b>⚠️ 반드시 {@code READ_COMMITTED} 트랜잭션 안에서 호출할 것.</b>
     * MySQL의 {@code INSERT ... SELECT}는 REPEATABLE READ(스프링 기본)에서 소스 테이블의 스캔 행에
     * shared next-key 락을 건다. 북마크 1,073만 행 기준 실측:
     * <pre>
     * ---TRANSACTION 2002, ACTIVE 198 sec
     * 28518 lock struct(s), heap size 4431992, 10730488 row lock(s)
     * </pre>
     * 이 동안 동시 세션의 {@code INSERT INTO bookmarks ...}가 {@code ERROR 1205 Lock wait timeout}으로
     * 죽는다. gap 락까지 잡히므로 새 북마크 삽입 자체가 막힌다. 같은 문장을 READ COMMITTED로 돌리면
     * 소스 테이블 락 0건, 동시 INSERT 즉시 성공. 현재 배치 소요는 6.0~6.2초라 체감이 작지만
     * 북마크가 3배 늘면 정지 구간도 ~18초로 함께 늘어난다.
     *
     * <p>RC로 낮추면 스캔 도중 커밋된 행이 집계에 일부 섞일 수 있다. 이 값은 애초에 "대략 지금"의
     * 스냅샷이고 24시간 stale을 수용하는 2급 데이터라 문제가 되지 않는다 — 다음 1회로 씻긴다.
     *
     * <p><b>반면 {@code place_stats} 읽기는 이 배치에 막히지 않는다.</b> 조회는 MVCC 일관된 읽기라
     * 배치가 도는 중에도 대기 없이 <em>직전 배치 결과</em>를 보고, 커밋 시점에 원자적으로 새 세대로
     * 바뀐다. 즉 읽기 경로(스냅샷 로더)는 반쯤 갱신된 중간 상태를 절대 보지 않는다.
     * 이 성질에 기대도 좋다.
     *
     * <p><b>{@code binlog_format} 전제:</b> RC + {@code INSERT ... SELECT}는 {@code STATEMENT}
     * 바이너리 로깅과 공존할 수 없다 — {@code ERROR 1665: impossible to write to binary log since
     * BINLOG_FORMAT = STATEMENT ... InnoDB is limited to row-logging when transaction isolation level
     * is READ COMMITTED}로 문장 자체가 거부된다(실측). mysql:8.0(8.0.41) 기본값이
     * {@code binlog_format = ROW}, {@code log_bin = 1}임을 확인했으므로 현재는 안전하다.
     * 운영 DB의 {@code @@binlog_format}이 STATEMENT/MIXED로 바뀌면 이 배치가 통째로 실패한다.
     *
     * <p><b>{@code VALUES(col)} 문법 — deprecated이며 경고가 실제로 뜬다.</b>
     * mysql:8.0(8.0.41) 실측 기준 이 문장 1회 실행마다
     * {@code Warning 1287: 'VALUES function' is deprecated ...}이 <b>7건</b>(ON DUPLICATE KEY UPDATE의
     * {@code VALUES(col)} 참조 개수만큼) 올라오고, 여기에 DOUBLE 점수를 {@code DECIMAL(18,6)}에
     * 넣으면서 잘리는 행마다 {@code Note 1265 Data truncated for column 'popular_score'}가 더 붙는다.
     * 후자는 행 수에 비례하므로 장소 6,000개면 최대 6,000건이다. INSERT 경로·UPDATE 경로 모두 동일.
     * 동작에는 영향이 없고(값은 정상 갱신된다) JDBC {@code SQLWarning}으로만 전달되지만,
     * 경고 로깅을 켜면 배치 1회당 이만큼이 로그에 남는다는 뜻이다.
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
     * @param bookmarkWeight 북마크 1건의 가중치. 감쇠 전 기여분이 그대로 이 값이다
     * @param reviewWeight   리뷰 1건의 가중치. 실제 기여는 (rating − 3)이 곱해져 −2배 ~ +2배가 된다
     * @param halfLifeDays   감쇠 반감기(일). 이 일수만큼 지난 활동의 기여가 절반이 된다.
     *                       <b>0을 넘기면 MySQL이 0으로 나눠 {@code NULL}을 만들고 COALESCE가 그것을
     *                       0으로 삼켜 전 장소 점수가 조용히 0이 된다. 음수는 감쇠가 아니라 증폭이 된다.</b>
     *                       그래서 {@code PlaceStatsProperties}에서 {@code @Positive}로 부팅 시점에 막는다
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
