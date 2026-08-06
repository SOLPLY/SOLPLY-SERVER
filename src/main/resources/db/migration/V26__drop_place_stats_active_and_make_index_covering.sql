-- V26: place_stats.active 제거 + 정렬 인덱스를 커버링으로 재생성
--
-- ① active 제거 근거 (2026-08-02 결정): 활성 필터의 진실은 places.active 하나로 충분하다.
--    조회는 JOIN places p ON p.active = 1 가드가 즉시 거르므로, ps.active의 실효는
--    인덱스 스캔 가지치기뿐이었다 — 비활성 장소가 희소해 실익 ~0. 반면 비용은 실재했다:
--    재활성화 직후 ps.active=0이 낡아 장소가 최대 24시간 목록에서 실종 (V24 주석이 경고한
--    "correctness가 깨진다"의 실제 방향은 노출이 아니라 누락이었다). 복제본을 없애면
--    낡을 것도 없다. 동기화 훅(구 플랜 C Q3)도 town_id 축만 남아 배치 간격 단축으로 갈음한다.
--
--    "선두 프리픽스에 active가 있어 스캔이 좁아진다"는 이점 주장은 순환 논리였다 —
--    가지치기의 값어치는 비활성 행이 많이 존재함을 전제하는데, 그 전제가 거짓이다.
--
-- ② bookmark_count를 인덱스 끝에 덧붙이는 근거 (2026-08-01 실측): 시 단위(town IN 18개)
--    조회가 커버링이 아니라서 옵티마이저가 인덱스를 버리고 풀스캔했다 (랜덤 1,800회 >
--    순차 6,440행, trace cost 1,947 vs 673). 커버링으로 만들면 6.46ms → 0.806ms (8배)이고
--    스캔이 테이블 전체가 아닌 해당 시의 행 수에 비례하게 된다 — 전국 확장 시 선형 증가 해소.
--    선두 프리픽스가 그대로라 동네 단위(21건 읽고 단락)는 손해 0.
--    쓰기 증폭(증분 UPDATE마다 인덱스 엔트리 갱신)은 저빈도 쓰기 대 40% 트래픽 읽기라 수용.
--
-- 롤백 주의: 이 마이그레이션 이후 구코드는 배치 UPSERT·정렬 쿼리 모두 실패한다 (ps.active 참조).
ALTER TABLE place_stats
    DROP INDEX idx_place_stats_town_score,
    ADD INDEX idx_place_stats_town_score (town_id, popular_score DESC, place_id, bookmark_count),
    DROP COLUMN active;
