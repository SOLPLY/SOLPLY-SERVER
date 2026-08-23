-- V38: 북마크 카운트 아웃박스. 토글 한 번이 행 하나로 남는 append-only 전표 테이블이다.
--
-- 매시 카운트 배치가 bookmarks 전량을 다시 세는 구조를 "지난 회차 이후의 변경분만 더한다"로
-- 바꾸기 위한 기반이다. 델타는 정의상 `지금 − 워터마크 시점`인데, 원본 테이블에는 마지막
-- 상태만 남아 그 차이를 복원할 수 없다 — 등록·해제를 반복한 사용자의 이력이 마지막 상태에
-- 뭉개지기 때문이다. 그래서 상태가 아니라 변화를 기록한다
-- (근거: docs/design/2026-08-17-bookmark-outbox-delta.md 3장).
--
-- ⚠️ 보조 인덱스도 FK도 두지 않는 것이 이 테이블의 계약이다. 배치가 전체를 읽고 지우므로
--    탐색이 필요 없어 PK면 족하고, 인덱스가 없어야 북마크 쓰기 경로에 얹는 비용이 진짜로
--    INSERT 하나로 끝난다. 인덱스를 하나 더는 순간 이 설계가 사려던 것(요청 시점에 아무
--    공유 지점도 건드리지 않는다)의 값이 깎인다. target_id에 FK를 걸지 않는 이유도 같다 —
--    전표는 도메인 참조가 아니라 소비되면 사라지는 기록이고, 대상이 지워졌다면 그 델타는
--    배치의 GREATEST(0, …)가 흡수한다.
--
-- 테이블은 늘 "마지막 소비 이후의 토글"만 들고 있어 작다. 소비가 밀리면 쌓였다가 다음
-- 회차에 한 번에 비워질 뿐이다.
--
-- delta는 +1/−1만 담으므로 TINYINT다. 이 저장소의 다른 수치 컬럼이 INT인 것(V22 주석)과
-- 갈리는 자리인데, 그 논의의 근거는 "폭을 아껴 얻는 게 없다"였고 여기서는 다르다 — 이 행은
-- 쓰기 경로마다 하나씩 생기고 배치가 전량을 읽어 가는 유일한 소비자라, 행 폭이 곧
-- 회차당 읽기량이다.
CREATE TABLE bookmark_count_events
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    target_type VARCHAR(30) NOT NULL,
    target_id   BIGINT      NOT NULL,
    delta       TINYINT     NOT NULL COMMENT '+1 = 북마크 등록, -1 = 해제',
    created_at  DATETIME(6) NOT NULL COMMENT '전표가 생긴 시각. 소비 순서는 id가 정하며 이 값은 운영 관찰용이다',

    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
