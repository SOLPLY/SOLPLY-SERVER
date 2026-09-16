-- V46: 목록 스냅샷의 공유 대상을 "내용"에서 "번호 둘"로 되돌린다.
--
-- V43·V45는 스냅샷 payload 자체를 DB에 싣고(place_list_publications) 한 인스턴스가 그것을 지어
-- 나머지가 내려받게 했다. 그 구조는 짓는 비용을 한 번으로 줄이는 대신 gzip(JSON) 수 MB를 매
-- 회차 왕복시키고, 소비자 소유권·포인터 CAS·작업 큐·기준 경쟁까지 함께 짊어졌다. 여기서는 각
-- 인스턴스가 자기 원본을 읽어 자기 스냅샷을 짓는다 — DB가 공유하는 것은 "무엇이 바뀌었나"를
-- 말하는 번호 둘뿐이다.
-- 상세: docs/design/2026-09-13-local-snapshot-revision.md
--
-- ⚠️ 번호 둘은 뜻이 다르다. 하나로 합치지 말 것.
--    revision       = "다시 지을 것이 있나". 목록에 실리는 무엇이든 바뀌면 오른다.
--    cursor_version = "지금 커서를 계속 써도 되나". 진행 중인 스크롤을 끊어야 할 때만 오른다.
--    합치면 이름 한 칸 고친 어드민 수정이 스크롤 세션을 전부 끊는다. 반대로 cursor_version만
--    두면 "스크롤 유지"를 고른 수정이 어느 인스턴스에도 반영되지 않는다.
--
-- ⚠️ revision은 cursor_version이 오르는 UPDATE에서 0으로 돌아간다. 그래서 번호 쌍은 "몇 회차의
--    몇 번째 변경"으로 읽히고, 두 번호의 전순서는 (cursor_version, revision) 사전식이다.
--    revision 하나만 대소 비교하면 회차가 오른 직후의 스냅샷(revision = 0)이 "낡았다"로 판정된다.
CREATE TABLE place_list_snapshot_metadata
(
    id             TINYINT     NOT NULL COMMENT '항상 1',
    revision       BIGINT      NOT NULL DEFAULT 0 COMMENT '다시 지을 것이 있나. 각 인스턴스의 폴이 자기 것과 대조하는 값',
    cursor_version BIGINT      NOT NULL DEFAULT 0 COMMENT '커서가 싣고 다니는 회차. 목록을 새로 시작시켜야 할 때만 오른다',
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- <b>정책: 이 마이그레이션이 도는 순간 진행 중이던 스크롤은 전부 만료된다.</b>
--
-- 옛 발행 회차의 최대값에 <b>1을 더해</b> 시작한다. 그래서 배포 직전에 발급된 커서는 자기가 싣고
-- 있는 번호와 여기 값이 달라 전부 만료로 끊긴다. 그대로 이어받으면(+1 없이) 마지막 발행의
-- 커서만 조용히 통과하는데, 그 커서가 가리키는 정렬 배열은 새 구조가 다시 지은 것이라 같은
-- 번호가 같은 회차를 뜻한다는 보장이 없다 — 번호만 맞고 내용이 다른 커서가 가장 나쁘다.
--
-- 0부터 다시 세지 않는 이유도 같다. 0부터면 언젠가 옛 커서의 번호에 다시 도달해, 그때 만료로
-- 끊어야 할 커서가 통과한다.
--
-- 발행이 하나도 없던 DB(신규·테스트)에서는 COALESCE가 0을 주어 cursor_version은 1에서 시작한다.
INSERT INTO place_list_snapshot_metadata (id, revision, cursor_version)
SELECT 1,
       COALESCE((SELECT MAX(id) FROM place_list_publications), 0),
       COALESCE((SELECT MAX(cursor_version) FROM place_list_publications), 0) + 1;

-- 옛 구조를 여기서 함께 정리한다. 스냅샷 내용을 DB에 두는 경로가 사라졌으므로 이 네 테이블을
-- 읽거나 쓰는 코드는 남아 있지 않다.
--
-- ⚠️ place_stats_job_schedules(V44)는 건드리지 않는다 — 집계 회차의 영속 요청은 이 변경의
--    대상이 아니다.
--
-- 순서가 있다. 포인터가 발행물을 FK로 가리키므로 포인터를 먼저 지운다.
DROP TABLE IF EXISTS place_list_publication_pointer;
DROP TABLE IF EXISTS place_list_publications;
DROP TABLE IF EXISTS place_list_snapshot_jobs;
DROP TABLE IF EXISTS place_list_snapshot_consumer;
DROP TABLE IF EXISTS place_list_rebuild_requests;
