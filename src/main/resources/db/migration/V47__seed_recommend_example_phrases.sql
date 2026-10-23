-- V47: 자연어 추천 예시 문구의 정본을 마이그레이션으로 옮긴다.
--
-- V18은 테이블만 만들었고 값은 dev에 손으로 넣어둔 상태였다. 그래서 prod에는 한 건도 없어
-- GET /api/recommend/example-phrases가 빈 배열을 내려주고 있었다. 환경마다 값이 갈리지 않도록
-- 고정 목록(동네 무관)을 여기에 둔다.
--
-- 전량 교체로 쓴다. content에 유니크 제약이 없어 덧붙이는 INSERT는 중복 행이 되고, 이 테이블의
-- collation(utf8mb4_0900_ai_ci)은 NO PAD라 손으로 넣은 값의 꼬리 공백 때문에 "같은 문구"로도
-- 잡히지 않는다. 비우고 id까지 명시해 넣으면 어느 환경에서 몇 번 돌아도 결과가 같다.
-- 이 테이블을 참조하는 외래키는 없다.
DELETE FROM recommend_example_phrases;
ALTER TABLE recommend_example_phrases AUTO_INCREMENT = 1;

INSERT INTO recommend_example_phrases (id, target_type, content, display_order) VALUES
    (1, 'PLACE', '조용한 독립서점', 0),
    (2, 'PLACE', '아기자기한 소품샵', 1),
    (3, 'PLACE', '작업하기 좋은 카페', 2),
    (4, 'PLACE', '디저트가 맛있는 카페', 3),
    (5, 'COURSE', '조용히 걷기 좋은 힐링 코스', 0),
    (6, 'COURSE', '소품샵 중심 취향 탐색 코스', 1),
    (7, 'COURSE', '카페나 디저트 맛집 코스', 2);
