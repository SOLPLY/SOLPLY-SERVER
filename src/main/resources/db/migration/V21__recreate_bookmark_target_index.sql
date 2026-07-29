-- 인기순 정렬(sort=popular) 도입 — V20에서 예고한 타겟 축 인덱스 재생성
-- 스냅샷 로드 시 장소별 북마크 수 집계(WHERE target_type AND target_id IN ... GROUP BY)의
-- 커버링(Using index) 처리용. 비용을 O(전체 테이블) → O(해당 장소 북마크 수)로 낮춘다.
CREATE INDEX idx_bookmark_target ON bookmarks (target_type, target_id);
