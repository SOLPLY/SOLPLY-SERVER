-- 상위 지역: 서울
INSERT INTO towns (id, name, parent_id) VALUES (1, '서울', NULL);

-- 하위 지역: 연희동, 망원동
INSERT INTO towns (id, name, parent_id) VALUES (2, '연희동', 1);
INSERT INTO towns (id, name, parent_id) VALUES (3, '망원동', 1);