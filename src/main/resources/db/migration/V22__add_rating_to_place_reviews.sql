-- V22: 리뷰 평점 — 인기순 복합 점수의 두 번째 축
--
-- 인기순 정렬(sort=POPULAR)은 누적 북마크 수 단일 축에서 북마크·리뷰·평점·최신성을 합성한
-- 복합 점수로 바뀐다. 그중 리뷰 축이 평점을 필요로 해서 이 컬럼을 신설한다.
-- 평점은 1~5 정수로 저장하고, 점수 계산 시 3점을 중심으로 (rating - 3)으로 환산해
-- 1~2점 리뷰가 순위를 끌어내리도록 한다. (환산·집계는 후속 작업 소관)
--
-- 운영 전 서비스라 기존 리뷰 데이터는 고려 대상이 아니지만, 로컬/개발 DB에는 테스트로 쌓인
-- 행이 남아 있을 수 있다. 중립값 3으로 채운 뒤 DEFAULT를 즉시 제거해
-- "앱이 값을 안 넣으면 조용히 3점이 되는" 상태를 남기지 않는다.
--
-- 타입은 INT. 1~5만 담으므로 폭으로는 TINYINT로 충분하지만,
-- 이 저장소의 마이그레이션에는 TINYINT/SMALLINT가 한 건도 없고 weight, display_order 같은
-- 소범위 컬럼도 전부 INT다. 값 범위는 아래 CHECK 제약이 지키므로
-- 폭을 줄여 얻는 3바이트보다 기존 선례와의 일관성이 낫다.
ALTER TABLE place_reviews ADD COLUMN rating INT NOT NULL DEFAULT 3;
ALTER TABLE place_reviews ALTER COLUMN rating DROP DEFAULT;
ALTER TABLE place_reviews
    ADD CONSTRAINT chk_place_reviews_rating CHECK (rating BETWEEN 1 AND 5);

-- 감쇠 집계 커버링 인덱스.
-- 인기순 점수 배치는 장소별 리뷰 축을
--   SUM(가중치 × (rating - 3) × POW(0.5, 경과일 / 반감기 90))
-- 형태로 집계한다. 한 장소의 리뷰를 훑으며 행마다 created_at(경과일)과 rating을 읽으므로,
-- (place_id, created_at, rating) 셋이 모두 인덱스에 있어야 테이블을 건드리지 않는
-- 인덱스 전용 스캔(Using index)으로 끝난다.
--
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
