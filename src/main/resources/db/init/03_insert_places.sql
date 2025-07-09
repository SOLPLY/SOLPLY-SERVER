-- PLACE 테이블
INSERT INTO places (id, name, introduction, address, contact_number, opening_hours, latitude, longitude, place_default_id, place_type, town_id, created_at, updated_at)
VALUES
    (1, '연희동 브런치카페', '분위기 좋은 브런치 카페', '서울시 서대문구 연희동 123', '02-123-4567', '10:00~21:00', 126.9286, 37.5665, 1001, 'CAFE', 2, now(), now()),
    (2, '책이 있는 공간', '조용한 독서를 위한 공간', '서울시 서대문구 연희동 456', '02-987-6543', '09:00~20:00', 124.9286, 32.5665, 1002, 'BOOKSTORE', 2, now(), now());