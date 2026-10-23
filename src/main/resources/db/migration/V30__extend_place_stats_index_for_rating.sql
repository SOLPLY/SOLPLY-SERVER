-- V30: 인기순 정렬 인덱스 말단에 review_count·avg_rating을 더해 커버링을 복원한다.
--
-- 배경. 목록 응답에 평점·리뷰 수가 실리면서 인기순 쿼리의 SELECT에 두 컬럼이 추가된다.
-- V29의 인덱스는 두 컬럼을 덮지 않으므로, 그대로 두면 페이지 행마다 클러스터드 인덱스
-- PRIMARY (place_id, version)로 되짚는 룩업이 붙는다 — 세컨더리 엔트리가 PK 두 컬럼을 이미
-- 들고 있어 등호 조회이긴 하나, 요청당 기본 페이지 크기(10)만큼 반복된다.
--
-- 왜 재는 대신 그냥 넣었는가. 효과 크기가 측정으로 판별되지 않는 구간이기 때문이다.
-- 대상 테이블은 장소 약 6,000 × 보관 2버전 ≈ 12,000행으로 버퍼풀에 상주하고, 요청 하나는
-- 이미 SQL 11개 × 문장당 고정 CPU 약 200us를 쓴다(2026-08-04 실측). 룩업 10회의 몫은 그
-- 아래로 묻혀 로컬 벤치 노이즈와 구분되지 않는다. 반대로 대가는 산술로 확정된다 —
-- 엔트리당 약 6B(INT 4 + DECIMAL(3,2) 2) × 12,000 ≈ 72KB, 쓰기 주체는 시간당 배치 1회뿐이다.
-- 그래서 "재 보니 빨랐다"가 아니라 "룩업이 사라지고 대가가 72KB"라는 근거로 채택했다.
--
-- 선두 프리픽스(version, town_id, popular_score DESC, place_id)가 그대로라 버전 파티션 절단·
-- 동네 필터·정렬·커서 타이브레이크의 동작은 바뀌지 않는다. 더한 두 컬럼은 정렬이나 탐색에
-- 참여하지 않고 오직 SELECT를 덮기 위해 말단에 있다.
--
-- 따라서 표시 항목이 늘 때마다 이 인덱스도 함께 늘어난다. 그 연동은
-- PlaceListDbQueryRepository#findPopularRows javadoc에 못 박아 뒀다.
DROP INDEX idx_place_stats_version_town_score ON place_stats;

CREATE INDEX idx_place_stats_version_town_score
    ON place_stats (version, town_id, popular_score DESC, place_id,
                    bookmark_count, review_count, avg_rating);
