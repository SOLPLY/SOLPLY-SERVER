-- #379: bookmarks 인덱스 확정 (100만 행 벤치마크 결과 기반)
-- 근거: docs/perf/2026-07-bookmark-index-benchmark.md §6
--   - p99 20~44% 개선 (S1 → S2b), 목록·batch IN 쿼리 커버링 처리
--   - cacheon(웜 Redis 캐시) 대비 DB 직행 동급 확인 — 캐시 제거(#377) 실측 검증

-- idx_bookmark_user_type은 uk_bookmark_user_target의 왼쪽 접두사라 중복 → 제거
DROP INDEX idx_bookmark_user_type ON bookmarks;

-- 동네별 목록(ORDER BY created_at)·폴더 프리뷰(윈도우 함수)·batch IN의
-- 정렬 활용 + 커버링(Using index)용 인덱스
CREATE INDEX idx_bookmark_user_type_created_target
    ON bookmarks (user_id, target_type, created_at DESC, target_id);
