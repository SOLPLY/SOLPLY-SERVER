-- V3__insert_tag_persona_mapping.sql

-- ANYTHING
INSERT IGNORE INTO tag_persona_mappings (tag_id, persona, weight) VALUES
  (1, 'ANYTHING', 1),  -- 카페
  (4, 'ANYTHING', 1),  -- 서점/책방
  (3, 'ANYTHING', 1),  -- 쇼핑
  (6, 'ANYTHING', 1),  -- 산책
  (5, 'ANYTHING', 1),  -- 이색공간
  (10, 'ANYTHING', 1), -- 힐링
  (13, 'ANYTHING', 1), -- 채광좋음
  (14, 'ANYTHING', 1), -- 콘센트많음
  (15, 'ANYTHING', 1), -- 시간제한없음
  (28, 'ANYTHING', 1), -- 소품샵
  (29, 'ANYTHING', 1), -- 빈티지샵
  (30, 'ANYTHING', 1), -- 팝업/플리마켓
  (26, 'ANYTHING', 1), -- 문화예술
  (27, 'ANYTHING', 1), -- 공방/클래스
  (21, 'ANYTHING', 1), -- 바/술집
  (11, 'ANYTHING', 1), -- 시그니처메뉴
  (12, 'ANYTHING', 1), -- 감성인테리어
  (16, 'ANYTHING', 1); -- 바테이블

-- REST: 조용한 공간에 오래 머물고 싶어요
INSERT IGNORE INTO tag_persona_mappings (tag_id, persona, weight) VALUES
  (1, 'REST', 1),  -- 카페
  (4, 'REST', 1),  -- 서점/책방
  (9, 'REST', 1),  -- 독서
  (10, 'REST', 1), -- 힐링
  (8, 'REST', 1),  -- 작업
  (13, 'REST', 1), -- 채광좋음
  (14, 'REST', 1), -- 콘센트많음
  (15, 'REST', 1); -- 시간제한없음

-- EXPLORER: 이곳저곳 가볍게 둘러보고 싶어요
INSERT IGNORE INTO tag_persona_mappings (tag_id, persona, weight) VALUES
  (3, 'EXPLORER', 1),  -- 쇼핑
  (6, 'EXPLORER', 1),  -- 산책
  (5, 'EXPLORER', 1),  -- 이색공간
  (28, 'EXPLORER', 1), -- 소품샵
  (29, 'EXPLORER', 1), -- 빈티지샵
  (30, 'EXPLORER', 1), -- 팝업/플리마켓
  (26, 'EXPLORER', 1), -- 문화예술
  (27, 'EXPLORER', 1); -- 공방/클래스

-- MOODING: 내 취향에 맞는 공간을 찾고싶어요
INSERT IGNORE INTO tag_persona_mappings (tag_id, persona, weight) VALUES
  (1, 'MOODING', 1),  -- 카페
  (5, 'MOODING', 1),  -- 이색공간
  (21, 'MOODING', 1), -- 바/술집
  (11, 'MOODING', 1), -- 시그니처메뉴
  (12, 'MOODING', 1), -- 감성인테리어
  (26, 'MOODING', 1), -- 문화예술
  (27, 'MOODING', 1), -- 공방/클래스
  (29, 'MOODING', 1); -- 빈티지샵

-- NATURAL: 풍경을 감상하며 쉬고 싶어요
INSERT IGNORE INTO tag_persona_mappings (tag_id, persona, weight) VALUES
  (6, 'NATURAL', 1),  -- 산책
  (1, 'NATURAL', 1),  -- 카페
  (5, 'NATURAL', 1),  -- 이색공간
  (10, 'NATURAL', 1), -- 힐링
  (13, 'NATURAL', 1), -- 채광좋음
  (16, 'NATURAL', 1); -- 바테이블