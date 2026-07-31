-- 배치 1회 = PlaceStatsRepository.upsertAll 한 문장.
--
-- 앱을 거치지 않고 SQL로 직접 돌리는 이유:
--   (a) 이 캠페인의 7개 항목이 전부 DB 레벨이고, 벤치 앱 이미지 빌드가 막혀 있다
--   (b) 격리수준을 RR/RC로 바꿔가며 같은 문장을 돌려야 하는데(항목 5), 앱은 RC로 고정돼 있다
--   (c) JPA·커넥션 풀 오버헤드가 빠져 DB 소요만 남는다 — 측정하려는 것이 그것이다
-- 대가: 앱 왕복 시간이 빠져 있으므로 "배치 전체 소요"의 하한이다. 결과 문서에 명시할 것.
--
-- 파라미터는 PlaceStatsProperties 기본값 (halfLife 90 / bookmark 1.0 / review 3.0).
-- calculatedAt은 호출자가 @cat으로 미리 세팅한다 — 회차마다 같은 값을 줘야 멱등성이 검증된다.
-- NOW()를 쓰면 회차마다 6번째 소수 자리가 갈려 "멱등"이라는 성질 자체를 잴 수 없다.

SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;

SET @t0 = NOW(6);
START TRANSACTION;

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
       @cat
FROM places p
LEFT JOIN (
    SELECT bm.target_id AS place_id,
           COUNT(*) AS cnt,
           SUM(1.0 * POW(0.5,
               TIMESTAMPDIFF(SECOND, bm.created_at, @cat) / 86400.0 / 90.0)) AS score
    FROM bookmarks bm
    WHERE bm.target_type = 'PLACE'
      AND bm.created_at <= @cat
    GROUP BY bm.target_id
) b ON b.place_id = p.id
LEFT JOIN (
    SELECT pr.place_id AS place_id,
           COUNT(*) AS cnt,
           AVG(pr.rating) AS avg_rating,
           SUM(3.0 * (pr.rating - 3) * POW(0.5,
               TIMESTAMPDIFF(SECOND, pr.created_at, @cat) / 86400.0 / 90.0)) AS score
    FROM place_reviews pr
    WHERE pr.created_at <= @cat
    GROUP BY pr.place_id
) r ON r.place_id = p.id
ON DUPLICATE KEY UPDATE
    town_id        = VALUES(town_id),
    active         = VALUES(active),
    popular_score  = VALUES(popular_score),
    bookmark_count = VALUES(bookmark_count),
    review_count   = VALUES(review_count),
    avg_rating     = VALUES(avg_rating),
    calculated_at  = VALUES(calculated_at);

COMMIT;
SELECT TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1000.0 AS batch_ms;

-- 지문 — 멱등성 판정용.
-- BIT_XOR(CRC32(...))는 순서 무관이라 정렬 없이 전 행을 하나의 값으로 접는다.
-- GROUP_CONCAT은 group_concat_max_len(기본 1024B)에 잘려 6,000행에서 조용히 틀린다.
SELECT COUNT(*)                                      AS rows_,
       SUM(popular_score)                            AS sum_score,
       MIN(popular_score)                            AS min_score,
       MAX(popular_score)                            AS max_score,
       BIT_XOR(CRC32(CONCAT_WS('|', place_id, town_id, active, popular_score,
                               bookmark_count, review_count,
                               COALESCE(avg_rating, '~'), calculated_at))) AS fingerprint
FROM place_stats;
