-- V45: 재빌드 "카운터 하나"를 종류가 있는 작업 큐로 바꾸고, 그것을 삼키는 소비자를 하나로 못 박는다.
--
-- V43의 place_list_rebuild_requests는 숫자 둘(requested_seq·processed_seq)이라 "무엇 때문에
-- 올라왔는지"를 말하지 못한다. 그래서 어드민이 이름 한 칸을 고쳐도 발행자는 원본 전량을 다시
-- 읽고 새 회차를 발급해 진행 중이던 커서를 전부 만료시킨다. 여기서는 요청에 종류와 대상을 실어,
-- 어드민 패치 묶음은 지금 발행물 위에 얹고 통계 회차만 전량으로 짓게 한다.
-- 상세: docs/design/2026-09-13-snapshot-work-consumer.md
--
-- ⚠️ place_list_rebuild_requests를 여기서 DROP하지 말 것. 롤아웃 중에는 옛 바이너리가 아직
--    그 카운터에 올리며 돌고(새 소비자가 그것도 함께 삼킨다), 롤백하면 다시 쓴다. 롤백 창이
--    닫힌 뒤 별도 마이그레이션으로 지운다 — V43이 V39를 남긴 것과 같은 이유다.

-- 갱신 작업 한 건. 생산자(어드민 쓰기·통계 회차)가 자기 트랜잭션의 마지막 문장으로 INSERT하고,
-- 발행에 성공한 트랜잭션만 DELETE한다. 대기 = 행이 있음, 완료 = 행이 없음. 그것이 전부다.
--
-- ⚠️ 상태 칸(PROCESSING)도 lease 만료 시각도 두지 않는다. "누가 잡고 있다"를 칸에 적으면 적어
--    놓고 죽은 행을 회수하는 장치가 따라오고, 그 장치의 만료가 다시 "살아 있는 소유자의 것을
--    빼앗는" 창을 연다. 소비자가 하나라는 것은 place_list_snapshot_consumer의 행 락이 말하고,
--    겹쳤을 때 진 쪽이 아무것도 남기지 못하게 하는 것은 포인터 CAS다 (V42·V44 주석과 같은 판단).
--
-- ⚠️ 지우는 문장은 언제나 "이번에 얼린 id 그 집합"이어야 한다. id <= 최대값으로 지우지 말 것 —
--    AUTO_INCREMENT는 발급 순서일 뿐 커밋 순서가 아니라, id 7을 받은 트랜잭션이 id 9보다 늦게
--    커밋할 수 있다. 범위로 지우면 아직 반영되지 않은 7번이 조용히 사라진다. 아웃박스에서 id
--    범위 삭제를 금지한 것(V38)과 포인터를 MAX(id)로 대신하지 말라는 것(V43)과 같은 뿌리다.
--    requested_at을 기준으로 지우는 것은 더 나쁘다 — 벽시계로 커밋 경계를 어림하는 것이다.
--
-- ⚠️ place_ids에 "값"을 싣지 말 것. 장소 id만 싣고 소비자가 그 id로 place_stats를 다시 읽는다.
--    값을 실으면 늦게 소비된 행이 남의 최신 값을 덮는다.
CREATE TABLE place_list_snapshot_jobs
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '이 작업의 신원. 완료 처리는 언제나 이 값의 집합으로 한다',
    job_kind     VARCHAR(16) NOT NULL COMMENT '코드의 SnapshotWorkKind 이름. FULL_REBUILD · PLACE_PATCH · TAG_REFRESH',
    place_ids    TEXT            NULL COMMENT 'PLACE_PATCH가 가리키는 장소 id, 쉼표로 이은 것. 나머지 종류는 NULL',
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '진단용. 이 값으로 지우지 않는다',

    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 소비자 소유권. 이 행의 X 락을 쥔 인스턴스가 그 순간의 유일한 소비자다.
--
-- ⚠️ 칸이 없는 것이 의도다. 소유자 이름도 만료 시각도 적지 않는다 — 만료가 있으면 "소유자가
--    아직 돌고 있는데 락만 풀린" 상태가 생기고(ShedLock lockAtMostFor가 그것이다), 소유자
--    이름을 적으면 그것을 지우는 장치가 또 따라온다. 행 락은 커밋·롤백·연결 종료 어느 쪽으로도
--    반드시 풀리므로 적어 둘 것이 없다.
--
-- ⚠️ 포인터 행(place_list_publication_pointer)으로 대신하지 말 것. 소유권 트랜잭션이 그 행을
--    잡은 채 발행 트랜잭션(REQUIRES_NEW — 다른 커넥션)이 같은 행에 CAS UPDATE를 걸면 서로를
--    기다려 풀리지 않는다. place_list_snapshot_jobs의 행으로 대신하는 것도 안 된다 — 거기는
--    생산자가 INSERT하는 자리라, 소유권을 걸면 생산자와 소비자가 같은 테이블을 마주 본다.
--    아무도 쓰지 않는 행 하나를 따로 두는 것이 가장 작은 장치다.
CREATE TABLE place_list_snapshot_consumer
(
    id         TINYINT     NOT NULL COMMENT '항상 1',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

INSERT INTO place_list_snapshot_consumer (id) VALUES (1);

-- 옛 카운터에 밀린 것이 있으면 새 큐로 옮긴다. 옛 요청은 "무엇이 바뀌었는지"를 말하지 않으므로
-- 전량으로 받는 것이 유일하게 안전하다. 밀린 것이 없으면 이 문장은 0행을 넣는다.
INSERT INTO place_list_snapshot_jobs (job_kind)
SELECT 'FULL_REBUILD'
  FROM place_list_rebuild_requests
 WHERE id = 1
   AND requested_seq > processed_seq;
