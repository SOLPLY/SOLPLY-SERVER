-- V24: 인기순 복합 점수 메타 테이블
--
-- 매일 02:00 배치가 원본(bookmarks/place_reviews)에서 전량 재계산해 이 테이블에 UPSERT하고,
-- 읽기 경로는 조회만 한다. 캐시 미스마다 동네 전체 북마크를 집계하던 비용이 사라진다.
--
-- places에 컬럼을 박지 않는 이유: 정렬 축이 늘 때마다 스키마가 늘고, 1급 개념(places)이
-- 언제든 재계산 가능한 2급 데이터로 오염된다. 별도 테이블이라 "24시간 stale 수용"이라는
-- 정확도 요구를 이 테이블에만 걸 수 있다.
CREATE TABLE place_stats
(
    place_id       BIGINT        PRIMARY KEY,
    town_id        BIGINT        NOT NULL,
    active         BOOLEAN       NOT NULL,
    popular_score  DECIMAL(18, 6) NOT NULL DEFAULT 0,
    bookmark_count INT           NOT NULL DEFAULT 0,
    review_count   INT           NOT NULL DEFAULT 0,
    avg_rating     DECIMAL(3, 2) NULL,
    calculated_at  DATETIME(6)   NOT NULL,

    CONSTRAINT fk_place_stats_place
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 플랜 C에서 정렬을 DB로 옮길 경우를 대비한 정렬 인덱스.
-- 지금은 어떤 쿼리도 타지 않는다(정렬은 메모리 유지). 스키마를 두 번 바꾸지 않으려고 함께 만든다.
-- town_id/active를 비정규화한 이유도 같다 — places와 JOIN하면 이 인덱스가 정렬에 쓰이지 못한다.
CREATE INDEX idx_place_stats_town_score
    ON place_stats (town_id, active, popular_score DESC, place_id);
