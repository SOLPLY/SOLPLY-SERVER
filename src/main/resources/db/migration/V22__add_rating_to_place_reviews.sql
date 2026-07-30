-- V22: 리뷰 평점 — 인기순 복합 점수의 두 번째 축
-- 설계: docs/superpowers/specs/2026-07-30-place-popular-score-design.md §0, §4.1
--
-- 운영 전 서비스라 기존 리뷰 데이터는 고려 대상이 아니지만, 로컬/개발 DB에는 테스트로 쌓인
-- 행이 남아 있을 수 있다. 중립값 3으로 채운 뒤 DEFAULT를 즉시 제거해
-- "앱이 값을 안 넣으면 조용히 3점이 되는" 상태를 남기지 않는다.
--
-- 타입은 INT. 1~5만 담으므로 TINYINT로 충분하지만, 엔티티 필드가 Integer라
-- TINYINT면 ddl-auto: validate가 "found [tinyint], but expecting [integer]"로 부팅을 막는다.
-- 값 범위는 아래 CHECK 제약이 지키므로 폭을 줄여 얻는 3바이트보다 매핑 일치가 낫다.
-- (weight, display_order 등 기존 소수값 컬럼도 모두 INT다)
ALTER TABLE place_reviews ADD COLUMN rating INT NOT NULL DEFAULT 3;
ALTER TABLE place_reviews ALTER COLUMN rating DROP DEFAULT;
ALTER TABLE place_reviews
    ADD CONSTRAINT chk_place_reviews_rating CHECK (rating BETWEEN 1 AND 5);

-- 감쇠 집계 커버링: SUM(POW(0.5, age/90)) 은 행마다 created_at을, 평점 축은 rating을 읽는다.
-- 기존 idx_place_reviews_place_id_created_at (place_id, created_at DESC)는 이 인덱스의
-- 왼쪽 접두사라 완전히 중복된다 — V19/V20에서 중복 인덱스를 걷어낸 것과 같은 이유로 제거한다.
-- (목록 조회 ORDER BY created_at DESC는 이 인덱스의 역방향 스캔으로 그대로 처리된다)
--
-- 순서 주의: place_id에는 전용 FK 인덱스가 따로 없고, fk_place_review_to_place_cascade가
-- 바로 이 idx_place_reviews_place_id_created_at에 얹혀 있다. 그래서 드롭을 먼저 하면
-- "Cannot drop index ...: needed in a foreign key constraint"로 마이그레이션이 깨진다(실측 확인).
-- 대체 인덱스를 먼저 만들어 FK가 옮겨 탈 자리를 준 뒤 드롭한다.
CREATE INDEX idx_place_reviews_place_created_rating
    ON place_reviews (place_id, created_at, rating);

DROP INDEX idx_place_reviews_place_id_created_at ON place_reviews;
