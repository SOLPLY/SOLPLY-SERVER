-- V32: place_stats를 "버전당 행 집합"에서 다시 "장소당 최신 행 1개"로 되돌리고,
--      집계를 카운트 배치(매시)와 인기점수 배치(매일 01:00 KST) 둘로 가른다.
--
-- 버전 행(V29)이 있던 이유는 하나였다 — 매시 배치가 도는 순간 스크롤 중이던 사용자의 좌표계가
-- 갈리는 것. 그 대가로 행 수 2배, 메타 레지스터 1행, 커서의 세대 필드, current/prev 판정,
-- 만료 오류, 옛 버전 청소, 그리고 "세 문장이 한 트랜잭션"이라는 계약이 따라왔다.
-- 인기점수를 새벽 1회로 내리면 갈림 창이 "전 시간대"에서 "트래픽 최저 시각의 수 초"로 줄고,
-- 그 순간 스크롤 중이던 소수 사용자가 겪는 희박한 중복·누락은 수용하기로 했다.
-- 남는 것이 위 장치 전부의 제거다.
--
-- 카운트와 점수를 가른 이유는 비용이 아니라 주기다. 둘은 여전히 원본 전량을 훑으므로 분리가
-- 스캔을 아끼지 않는다(오히려 bookmarks·place_reviews를 각각 한 번씩 더 훑는다). 대신
-- 표시용 북마크 수·리뷰 수·평점은 ≤1h 신선도를 유지하면서, 90일 반감기라 하루쯤 낡아도
-- 순위가 흔들리지 않는 점수만 새벽으로 내릴 수 있다.
--
-- 계산 시각을 두 컬럼으로 나눈 것이 이 스키마의 핵심 계약이다.
--   count_calculated_at — 카운트 배치가 이번 회차에 이 행을 건드렸다는 표식. NOT NULL이고
--     회차마다 갱신된다. 값이 이번 회차와 다른 행 = 이번 회차의 활성 장소 목록에 없던 행,
--     즉 비활성화·삭제된 장소의 잔행이다. 버전 청소가 겸업하던 그 삭제를 이 컬럼이 대신한다
--     (V29에서는 옛 버전이 통째로 죽으면서 잔행도 함께 사라졌다).
--   score_calculated_at — 인기점수 배치가 마지막으로 이 행의 점수를 정한 시각. 카운트 배치가
--     새로 만든 행은 NULL이며 popular_score는 DEFAULT 0이다. "아직 점수가 없다"와 "0점이다"를
--     구분해야 (a) 기동 시 점수 백필 여부를 판정하고 (b) 인기순 조회가 미채점 행을 걸러낼 수 있다.
--     ⚠️ (b)를 지우면 미채점 DEFAULT 0이 유효한 음수 점수보다 위에 온다 — 리뷰 축이
--     w₂ × (조정평점 − C)라 저평점 장소의 점수는 실제로 음수이고, 그러면 아직 아무 평가도 받지
--     않은 신규 장소가 평판 나쁜 장소를 제친다. 근거는 PlaceListDbQueryRepository#findPopularRows.
-- 한 컬럼으로 합치면 두 배치가 서로의 표식을 덮어써 위 판정이 모두 무너진다.
--
-- ⚠️⚠️ 이 마이그레이션은 롤링 배포와 호환되지 않는다 — 배포 창 동안 구버전 인스턴스가 죽는다.
-- (1) DROP TABLE이 먼저 돌므로 그 순간부터 구버전이 place_stats를 읽으면 테이블이 없거나(찰나)
--     재생성된 뒤여도 version 컬럼이 없다. 구버전 인기순 쿼리는 WHERE ps.version = :version,
--     최신순 조인은 place_stats_meta 스칼라 서브쿼리라 둘 다 ERROR 1054/1146으로 죽는다.
-- (2) place_stats_meta를 DROP하므로 구버전의 PlaceStatsMetaRepository.findGenerations()도 같이 죽고,
--     그것은 인기순 요청 경로 위에 있다.
-- 코드로 우회하지 않는다. 우회하려면 구·신 스키마를 동시에 만족하는 중간 형상을 한 벌 더 만들어야
-- 하는데(version 컬럼 유지 + meta 유지 + 신규 컬럼 추가 → 다음 배포에서 제거), 그것은 배포를 두 번
-- 나누고 그 사이 두 스키마를 모두 아는 코드를 유지한다는 뜻이다. place_stats는 원본에서 언제든
-- 복원되는 파생 테이블이고 운영 전이라 그 비용을 지불할 이유가 없다.
-- ⇒ 배포 절차: 구버전 인스턴스를 전부 내린 뒤 신버전을 올린다(중단 배포). 인기순은 신버전 기동
--    백필이 카운트·점수를 채울 때까지 비어 있고, 그 창은 초 단위다.
--
-- 이관하지 않고 재생성하는 이유는 V29와 같다 — place_stats는 원본(bookmarks/place_reviews)에서
-- 언제든 전량 복원되는 파생 테이블이고, 기동 시 최초 적재가 카운트와 점수를 차례로 메운다.
-- ⚠️ 여기서 백필하지 말 것. Flyway는 자기 트랜잭션(기본 REPEATABLE READ)에서 돌아 집계
-- INSERT ... SELECT가 bookmarks 전체에 shared next-key 락을 걸고, 무중단 배포 중이면 동시
-- 북마크 INSERT가 ERROR 1205로 죽는다(실측 근거는 PlaceStatsRepository#upsertCounts javadoc).
-- 백필은 READ_COMMITTED 경계를 이미 갖춘 PlaceStatsFacade#backfillPlaceStatsOnStartup이 맡는다.
--
-- DROP과 첫 적재 사이에는 인기순이 빈다(기준 테이블이 place_stats다). 무중단 배포면 기동
-- 백필이 즉시 메우므로 창은 초 단위다.
DROP TABLE place_stats;

CREATE TABLE place_stats
(
    place_id            BIGINT         NOT NULL,
    town_id             BIGINT         NOT NULL,
    popular_score       DECIMAL(18, 6) NOT NULL DEFAULT 0,
    bookmark_count      INT            NOT NULL DEFAULT 0,
    review_count        INT            NOT NULL DEFAULT 0,
    avg_rating          DECIMAL(3, 2)  NULL,
    count_calculated_at DATETIME(6)    NOT NULL COMMENT '카운트 배치가 이 행을 마지막으로 건드린 회차의 기준 시각',
    score_calculated_at DATETIME(6)    NULL COMMENT '인기점수 배치가 이 행의 점수를 마지막으로 정한 시각. NULL = 아직 채점 전',

    PRIMARY KEY (place_id),
    CONSTRAINT fk_place_stats_place
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- V30 인덱스에서 선두의 version을 걷어내고 말단에 score_calculated_at을 더한 형태다. 동네 필터 →
-- 점수 정렬 → place_id 타이브레이크가 그대로 남고, 말단 네 컬럼이 목록 응답의 표시값과 채점 여부를
-- 덮어 커버링을 만든다(채택 근거는 V26·V30 주석).
--
-- score_calculated_at이 말단에 있는 이유는 그것이 탐색 범위를 좁히지 않기 때문이다. 인기순 쿼리의
-- IS NOT NULL은 선두 프리픽스(town_id, popular_score)가 잘라낸 range 안에서 행마다 평가되는 필터라
-- 순서상 뒤에 와야 하고, 그럼에도 인덱스에 실려 있어야 걸러낼 행마다 PRIMARY 룩업이 붙지 않는다.
-- SELECT에 표시 컬럼을 더하거나 WHERE에 place_stats 컬럼을 더할 때는 이 말단도 함께 늘려야 한다 —
-- PlaceListDbQueryRepository#findPopularRows javadoc에 그 연동을 못 박아 뒀다.
CREATE INDEX idx_place_stats_town_score
    ON place_stats (town_id, popular_score DESC, place_id,
                    bookmark_count, review_count, avg_rating, score_calculated_at);

-- 세대 레지스터는 존재 이유가 통째로 사라졌다. 커서는 더 이상 세대를 싣지 않고, "언제 계산했는가"는
-- 위 두 컬럼이 행 단위로 답한다. 남겨 두면 아무도 읽지 않는 1행이 스키마에 남아 다음 사람이
-- "이건 왜 있지"를 되묻게 된다.
DROP TABLE place_stats_meta;
