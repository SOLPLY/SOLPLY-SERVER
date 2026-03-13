ALTER TABLE tags ADD COLUMN meaning TEXT;

-- MAIN 태그 (id 1~6)
UPDATE tags SET meaning = '커피나 음료를 마시며 혼자 머물거나 쉬기 좋은 음료 중심 공간이다.' WHERE id = 1;
UPDATE tags SET meaning = '혼자 한 끼 식사를 즐기기 좋은 식당 또는 식사 중심 공간이다.' WHERE id = 2;
UPDATE tags SET meaning = '개성 있는 물건을 구경하고 구매할 수 있는 쇼핑 공간이다.' WHERE id = 3;
UPDATE tags SET meaning = '책을 구경하거나 조용히 시간을 보낼 수 있는 서점 공간이다.' WHERE id = 4;
UPDATE tags SET meaning = '일상과 다른 색다른 경험을 할 수 있는 특별한 공간이다.' WHERE id = 5;
UPDATE tags SET meaning = '걷고 둘러보며 여유롭게 즐길 수 있는 야외 공간이다.' WHERE id = 6;

-- OPTION1 태그 - 카페 계열 (id 7~10)
UPDATE tags SET meaning = '다양한 커피와 디저트 메뉴를 즐길 수 있는 곳이다.' WHERE id = 7;
UPDATE tags SET meaning = '노트북 작업이나 개인 업무를 하기 좋은 공간이다.' WHERE id = 8;
UPDATE tags SET meaning = '조용히 책을 읽으며 시간을 보내기 좋다.' WHERE id = 9;
UPDATE tags SET meaning = '복잡한 일상에서 벗어나 여유롭게 쉬어가기 좋은 분위기다.' WHERE id = 10;

-- OPTION2 태그 - 카페 계열 (id 11~16)
UPDATE tags SET meaning = '이 장소만의 특별한 시그니처 메뉴가 있다.' WHERE id = 11;
UPDATE tags SET meaning = '감각적인 인테리어로 사진 찍기 좋은 분위기다.' WHERE id = 12;
UPDATE tags SET meaning = '채광이 좋아 밝고 쾌적한 환경이다.' WHERE id = 13;
UPDATE tags SET meaning = '콘센트가 많아 장시간 작업하기 편리하다.' WHERE id = 14;
UPDATE tags SET meaning = '시간 제한 없이 여유롭게 머물 수 있다.' WHERE id = 15;
UPDATE tags SET meaning = '바 테이블이 있어 혼자 방문해도 어색하지 않다.' WHERE id = 16;

-- OPTION1 태그 - 음식 계열 (id 17~23)
UPDATE tags SET meaning = '정겨운 한식 메뉴를 혼자 편하게 즐길 수 있는 곳이다.' WHERE id = 17;
UPDATE tags SET meaning = '볶음, 면, 만두 등 다양한 중식 메뉴를 즐길 수 있다.' WHERE id = 18;
UPDATE tags SET meaning = '라멘, 덮밥, 카츠 등 다양한 일식 메뉴를 맛볼 수 있다.' WHERE id = 19;
UPDATE tags SET meaning = '파스타, 샌드위치 등 다양한 양식 메뉴를 즐길 수 있다.' WHERE id = 20;
UPDATE tags SET meaning = '저녁 시간 조용히 음료와 술을 혼자 즐기기 좋은 곳이다.' WHERE id = 21;
UPDATE tags SET meaning = '갓 구운 빵과 베이커리 메뉴를 맛볼 수 있는 곳이다.' WHERE id = 22;
UPDATE tags SET meaning = '동남아, 인도 등 다양한 아시아 음식을 맛볼 수 있다.' WHERE id = 23;

-- OPTION2 태그 - 음식 계열 (id 24~25)
UPDATE tags SET meaning = '혼자 방문해도 주문하기 편한 1인 메뉴가 있다.' WHERE id = 24;
UPDATE tags SET meaning = '셀프서비스 방식으로 편하게 이용할 수 있다.' WHERE id = 25;

-- OPTION1 태그 - 이색공간 계열 (id 26~27)
UPDATE tags SET meaning = '전시, 공연 등 문화와 예술을 체험할 수 있는 공간이다.' WHERE id = 26;
UPDATE tags SET meaning = '직접 만들고 배우는 공방 클래스를 즐길 수 있다.' WHERE id = 27;

-- OPTION1 태그 - 쇼핑 계열 (id 28~30)
UPDATE tags SET meaning = '감각적인 소품과 오브제를 구경하고 구매할 수 있다.' WHERE id = 28;
UPDATE tags SET meaning = '희귀하고 개성 있는 빈티지 아이템을 발견할 수 있는 곳이다.' WHERE id = 29;
UPDATE tags SET meaning = '팝업스토어나 플리마켓에서 다양한 브랜드와 상품을 만날 수 있다.' WHERE id = 30;

-- COURSE 태그 (id 31~34) - retrieval_text에 사용되지 않아 meaning 미설정
