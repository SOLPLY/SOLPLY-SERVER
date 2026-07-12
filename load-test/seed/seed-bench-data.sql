-- 북마크 인덱스 벤치마크 시드 (#379)
-- users 10만 + bookmarks 100만 (user당 PLACE 9 + COURSE 1)
-- 멱등: bench_ 프리픽스 데이터만 삭제 후 재생성
SET @@cte_max_recursion_depth = 200000;

DELETE b FROM bookmarks b JOIN users u ON u.id = b.user_id WHERE u.email LIKE 'bench\_%@bench.local';
DELETE FROM users WHERE email LIKE 'bench\_%@bench.local';

-- 1) 합성 사용자 10만
INSERT INTO users (role, nickname, email, is_new_user, is_deleted)
WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 100000)
SELECT 'USER', CONCAT('bench_', n), CONCAT('bench_', n, '@bench.local'), FALSE, FALSE FROM seq;

-- 2) 장소 북마크 9개/user (k*13 오프셋: places 수(320)와 서로소 → k별 rn 유일 → uk 충돌 없음)
INSERT INTO bookmarks (user_id, target_type, target_id, created_at)
SELECT u.id, 'PLACE', p.id,
       NOW() - INTERVAL FLOOR(RAND() * 365) DAY - INTERVAL FLOOR(RAND() * 86400) SECOND
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM users WHERE email LIKE 'bench\_%@bench.local') u
JOIN (SELECT 0 AS k UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8) ks
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn, COUNT(*) OVER () AS cnt
      FROM places WHERE active = 1) p
  ON p.rn = ((u.rn + ks.k * 13) % p.cnt) + 1;

-- 3) 코스 북마크 1개/user
INSERT INTO bookmarks (user_id, target_type, target_id, created_at)
SELECT u.id, 'COURSE', c.id,
       NOW() - INTERVAL FLOOR(RAND() * 365) DAY - INTERVAL FLOOR(RAND() * 86400) SECOND
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM users WHERE email LIKE 'bench\_%@bench.local') u
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn, COUNT(*) OVER () AS cnt
      FROM courses WHERE active = 1) c
  ON c.rn = (u.rn % c.cnt) + 1;

-- 4) 검증용 출력
SELECT
  (SELECT COUNT(*) FROM users WHERE email LIKE 'bench\_%@bench.local') AS bench_users,
  (SELECT COUNT(*) FROM bookmarks) AS total_bookmarks;
ANALYZE TABLE bookmarks;
