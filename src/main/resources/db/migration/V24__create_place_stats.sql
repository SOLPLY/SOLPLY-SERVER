-- V24: 인기순 복합 점수 메타 테이블
--
-- 매일 02:00 배치가 원본(bookmarks/place_reviews)에서 전량 재계산해 이 테이블에 UPSERT하고,
-- 읽기 경로는 조회만 한다. 캐시 미스마다 동네 전체 북마크를 집계하던 비용이 사라진다.
--
-- places에 컬럼을 박지 않는 이유: 정렬 축이 늘 때마다 스키마가 늘고, 1급 개념(places)이
-- 언제든 재계산 가능한 2급 데이터로 오염된다. 별도 테이블이라 "24시간 stale 수용"이라는
-- 정확도 요구를 이 테이블에만 걸 수 있다.
--
-- ⚠️ 단, "24시간 stale 수용"은 점수 컬럼에만 해당한다. active/town_id는 성질이 다르다.
-- 점수가 낡으면 순위만 흔들리지만, active가 낡으면 correctness가 깨진다 — 플랜 C에서
-- 아래 idx_place_stats_town_score로 정렬을 옮기는 순간 비활성화된 장소가 결과 집합에 그대로
-- 남아 내린 장소가 최대 하루 더 노출된다. town_id도 같은 방식으로 장소가 엉뚱한 동네에 낀다.
-- 두 컬럼 모두 실제 변경 경로가 살아 있다(AdminPlaceRepository.updateActiveByTownId가 동네
-- 비활성화 시 소속 장소를 일괄 false로, Place.update(...)가 소속 동네 변경).
-- 따라서 플랜 C 전환 시 이 두 컬럼을 그대로 신뢰하지 말고, places와 대조해 필터하거나
-- 어드민 변경 시 place_stats를 동기 갱신해야 한다.
--
-- 컬럼 폭 근거(V22의 INT 대 TINYINT 논의와 같은 결의 기록):
--   popular_score DECIMAL(18,6) — 소수부 6은 감쇠항 POW(0.5, age/90)이 만드는 잔값을 순위 비교가
--     가능한 수준으로 남기는 폭. 정수부는 18-6=12자리로, 1위 장소 북마크 ~7만 건에 리뷰 축
--     최대 가중치(3.0 × 2)를 더해도 6자리를 넘지 않아 여유가 크다. DECIMAL은 기본 signed라
--     저평점(rating<3)이 만드는 음수 점수도 그대로 담긴다.
--   avg_rating DECIMAL(3,2) — 1.00~5.00만 담으므로 정수부 1자리로 충분(최대 9.99).
--   bookmark_count/review_count INT — 장소 하나의 건수라 INT 상한(21억)과는 자릿수가 멀다.
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
-- (비정규화의 대가는 위에 적은 active/town_id stale 문제다. 함께 읽을 것)
-- 후행 place_id는 InnoDB가 세컨더리 인덱스에 PK를 암묵적으로 붙이므로 저장 관점에선 중복이지만,
-- 점수 동점 시의 타이브레이커이자 키셋 페이지네이션의 커서 축이라는 의도를 드러내려 명시한다.
CREATE INDEX idx_place_stats_town_score
    ON place_stats (town_id, active, popular_score DESC, place_id);
