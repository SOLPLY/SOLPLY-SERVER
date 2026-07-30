-- V23: 북마크 감쇠 집계 커버링 복원
--
-- 인기순 복합 점수 배치가 북마크 축을
--   SUM(가중치 × POW(0.5, 경과일 / 반감기 90))
-- 형태로 집계한다. V21의 idx_bookmark_target (target_type, target_id)는 COUNT(*)에는 커버링이지만,
-- 감쇠 SUM은 행마다 created_at을 읽어야 해 커버링이 깨지고 PK 룩업이 행 수만큼 붙는다.
-- created_at을 인덱스에 포함시켜 배치 집계를 인덱스 전용 스캔(Using index)으로 되돌린다.
--
-- 드롭 순서 주의(V22의 place_reviews 사례 대조): bookmarks의 유일한 FK인 fk_bookmarks_user는
-- user_id 축이고, 그 자리는 uk_bookmark_user_target·idx_bookmark_user_type_created_target이
-- 이미 받치고 있다. idx_bookmark_target에 얹힌 FK가 없어 선(先)드롭이 안전하다
-- (V20이 같은 이름의 인덱스를 단독 DROP으로 제거한 전례가 실측 근거).
DROP INDEX idx_bookmark_target ON bookmarks;
CREATE INDEX idx_bookmark_target ON bookmarks (target_type, target_id, created_at);
