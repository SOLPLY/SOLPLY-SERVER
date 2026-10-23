-- V28: 랭킹 세대 고정 — 직전 세대 점수 한 벌 보관 + 세대 메타
--
-- 문제: 스크롤 세션 도중 배치가 돌면 popular_score가 통째로 갈린다. 커서는 "점수 X, id Y 다음"이라는
-- 좌표인데 그 좌표계가 페이지 사이에 바뀌므로, 다음 페이지가 이미 본 장소를 다시 주거나(중복)
-- 아직 안 본 장소를 건너뛴다(누락). 매시 배치로 바꾸면서 이 창이 새벽 한 번에서 매시간으로 퍼졌다.
--
-- 해법: 배치가 현 점수를 prev_popular_score로 밀어내고, 커서에 세대 식별자를 실어
-- "커서를 발급한 세대의 점수 컬럼"으로 정렬한다. 한 세대만 보관하는 이유는 스크롤 세션의 수명이
-- 배치 간격(1h)을 넘는 경우가 드물고, N세대 보관은 컬럼이 아니라 이력 테이블이 필요한 다른 설계라서다.
-- 2세대 이상 지난 커서는 현 세대로 강등한다 — 오늘 이미 수용 중인 트레이드오프와 같은 동작이다.
--
-- NULL의 뜻: "이전 세대에 이 장소가 존재하지 않았다". 배치 이후 새로 생긴 장소가 그렇고,
-- prev 정렬은 이 행을 제외하는 것이 정확한 의미론이다(그 세대의 목록에 없던 장소다).
-- 그래서 DEFAULT 0이 아니라 NULL이다 — 0으로 채우면 신규 장소가 이전 세대 목록 꼬리에 유령으로 낀다.
--
-- 인덱스는 idx_place_stats_town_score와 같은 모양의 prev 판이다. 컬럼 구성이 같은 이유는
-- prev 정렬 쿼리가 현 세대 쿼리와 술어·정렬·타이브레이크가 전부 같고 점수 컬럼만 다르기 때문이다
-- (커버링을 만드는 끝의 bookmark_count 포함 — 근거는 V26 주석).
ALTER TABLE place_stats
    ADD COLUMN prev_popular_score DECIMAL(18, 6) NULL
        COMMENT '직전 배치 세대의 popular_score. NULL = 그 세대에 존재하지 않던 장소',
    ADD INDEX idx_place_stats_town_prev_score
        (town_id, prev_popular_score DESC, place_id, bookmark_count);

-- 세대 식별자의 저장소. 1행만 존재하며 배치가 UPSERT와 같은 트랜잭션에서 원자 갱신한다.
--
-- MAX(calculated_at)으로 유도하지 않는 이유: 증분(PlaceStatsIncrementListener)이 만드는 신규 행의
-- calculated_at은 NULL이고, 그 컬럼의 뜻은 "이 행을 마지막으로 정산한 기준 시각"이라 행마다 다를 수
-- 있다. 세대는 "배치 한 회차 전체"를 가리키는 값이므로 행에서 유도하면 의미가 오염된다.
--
-- id CHECK (id = 1): 이 테이블은 엔티티 집합이 아니라 단일 레지스터다. 2행이 생기는 순간
-- "현 세대"가 둘이 되어 조회가 어느 쪽을 읽느냐에 따라 정렬 컬럼이 갈린다. 스키마로 막는다.
-- NULL 초기값은 "아직 배치가 한 번도 안 돌았다"는 정확한 표현이다 — 첫 배치가 채운다.
CREATE TABLE place_stats_meta
(
    id                 TINYINT     NOT NULL PRIMARY KEY CHECK (id = 1),
    current_generation DATETIME(6) NULL,
    prev_generation    DATETIME(6) NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

INSERT INTO place_stats_meta (id, current_generation, prev_generation) VALUES (1, NULL, NULL);
