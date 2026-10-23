-- V23: 북마크 감쇠 집계 커버링 복원
--
-- 인기순 복합 점수 배치가 북마크 축을
--   SUM(가중치 × POW(0.5, 경과일 / 반감기 90))
-- 형태로 집계한다. V21의 idx_bookmark_target (target_type, target_id)는 COUNT(*)에는 커버링이지만,
-- 감쇠 SUM은 행마다 created_at을 읽어야 해 커버링이 깨지고 PK 룩업이 행 수만큼 붙는다.
-- created_at을 인덱스에 포함시켜 배치 집계를 인덱스 전용 스캔(Using index)으로 되돌린다.
--
-- 한 ALTER로 합친 이유 — 이 인덱스는 데드 인덱스가 아니다.
-- BookmarkRepository.countByPlaceIds(타겟 축 GROUP BY)가 쓰고 있고, TownPlacesSnapshotLoader가
-- 캐시 미스마다 이를 호출한다. DROP과 CREATE를 두 문장으로 나누면 그 사이에 인덱스 공백 구간이
-- 생겨 해당 쿼리가 풀스캔한다. MySQL은 한 ALTER 안에서 같은 이름을 드롭·추가할 수 있고
-- 이를 단일 in-place DDL로 처리하므로 공백이 사라진다.
--
-- FK 안전성: bookmarks의 유일한 FK인 fk_bookmarks_user는 user_id 축이고, 그 자리는
-- uk_bookmark_user_target·idx_bookmark_user_type_created_target이 이미 받치고 있다.
-- idx_bookmark_target에 얹힌 FK가 없어 드롭이 막히지 않는다
-- (V20이 같은 이름의 인덱스를 단독 DROP으로 제거한 전례가 실측 근거).
-- V22가 create-then-drop 순서였던 것은 FK 때문이었고, 여기는 그 제약이 없어 같은 패턴을 따를 이유가 없다.
--
-- 운영 적용 주의 (bookmarks는 벤치 시드 기준 1,039만 행):
--   (a) 이 규모의 인덱스 재구축은 수 분 단위로 걸린다. 배포 창을 그만큼 잡아야 한다.
--   (b) in-place DDL이라 재구축 중에도 읽기·쓰기는 열려 있지만, DDL의 시작과 종료 시점에
--       메타데이터 락(MDL)이 필요하다. 그때 장기 트랜잭션이 하나라도 열려 있으면 DDL이 그 뒤에서
--       대기하고, 이후 도착하는 bookmarks의 모든 쿼리가 그 DDL 뒤로 줄 선다(사실상 테이블 전체 정지).
--       적용 전 장기 트랜잭션이 없는지 확인할 것.
ALTER TABLE bookmarks
    DROP INDEX idx_bookmark_target,
    ADD  INDEX idx_bookmark_target (target_type, target_id, created_at);
