-- V29: place_stats를 "세대 = 컬럼 쌍"에서 "배치 1회차 = 버전 1개의 행 집합"으로 바꾼다.
--
-- 컬럼 쌍(popular_score/prev_popular_score)은 세대 고정을 코드 계약으로 들고 있었다 —
-- ON DUPLICATE KEY UPDATE의 대입 순서, 점수 컬럼을 동적으로 갈아 끼우는 SQL 조립 세 자리,
-- 인덱스 2벌, "prev IS NOT NULL = 그 세대에 없던 장소"라는 의미론 주석. 버전 행에서는
-- 그 전부가 WHERE version = :v 하나로 대체되고, 행이 없으면 없는 것이라 의미론이 구조에 내장된다.
--
-- version이 DATETIME(6)이 아니라 BIGINT(epoch 초)인 이유: 커서는 이미 세대를 초로 싣고 다닌다.
-- 마이크로초를 저장하면 커서 값으로 등호를 치는 순간 정밀도가 어긋나 0행이 된다. 쓰는 시점에
-- 한 번 초로 좁히면 함정 자체가 없다. meta 컬럼도 같은 표현으로 바꿔 변환 코드를 없앤다.
--
-- calculated_at을 없애는 이유: "이 행을 정산한 회차"라는 뜻이 version에 그대로 흡수된다.
-- 증분 폐지로 NULL 센티널("아직 정산 안 됨")의 용처도 함께 사라졌다.
--
-- 이관하지 않고 재생성하는 이유: place_stats는 원본(bookmarks/place_reviews)에서 언제든 전량
-- 복원되는 2급 데이터고 배치가 부팅 시 최초 적재를 한다. 배포 직후 첫 적재까지의 공백은
-- 빈 목록 + 옛 커서 만료 오류로 나타나며 1회성 비용으로 수용한다.
--
-- 상세: docs/design/2026-08-05-place-stats-version-rows.md §1~§2
DROP TABLE place_stats;

CREATE TABLE place_stats
(
    place_id       BIGINT         NOT NULL,
    version        BIGINT         NOT NULL COMMENT '배치 회차의 calculatedAt을 epoch 초(UTC 간주)로 좁힌 값',
    town_id        BIGINT         NOT NULL,
    popular_score  DECIMAL(18, 6) NOT NULL DEFAULT 0,
    bookmark_count INT            NOT NULL DEFAULT 0,
    review_count   INT            NOT NULL DEFAULT 0,
    avg_rating     DECIMAL(3, 2)  NULL,

    PRIMARY KEY (place_id, version),
    CONSTRAINT fk_place_stats_place
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 선두의 version은 상시 2값이라 저카디널리티지만 문제가 아니다 — 항상 등호로만 쓰여 자기 버전의
-- 파티션을 정확히 잘라내고, 그 안에서 기존과 같은 (town, 점수 DESC, place_id) 정렬이 성립한다.
-- 끝의 bookmark_count는 커버링을 만든다(근거는 V26 주석).
CREATE INDEX idx_place_stats_version_town_score
    ON place_stats (version, town_id, popular_score DESC, place_id, bookmark_count);

-- 세대 이름표도 같은 표현(epoch 초)으로 통일한다. DATETIME → BIGINT 변환을 시도하지 않도록
-- 값을 먼저 비우고 타입을 바꾼다 — 어차피 재생성된 place_stats에는 그 세대의 행이 없다.
UPDATE place_stats_meta
SET current_generation = NULL,
    prev_generation    = NULL
WHERE id = 1;

ALTER TABLE place_stats_meta
    MODIFY current_generation BIGINT NULL COMMENT '현 버전. place_stats.version과 같은 표현',
    MODIFY prev_generation    BIGINT NULL COMMENT '직전 버전. NULL이면 배치가 한 번만 돌았다';
