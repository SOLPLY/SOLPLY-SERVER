-- #379: 미사용 타겟 축 인덱스 제거
-- 현재 애플리케이션 쿼리는 전부 user 축(user_id 선행)으로 조회하며,
-- 목록 쿼리의 JOIN도 places/courses PK를 사용 — 이 인덱스를 타는 쿼리가 없음.
-- 타겟 축 조회(인기 집계, 대상 삭제 시 정리)가 도입되면 그때 재생성한다.
DROP INDEX idx_bookmark_target ON bookmarks;
