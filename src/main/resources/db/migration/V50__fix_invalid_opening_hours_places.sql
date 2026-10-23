UPDATE places
SET opening_hours = '월, 화, 목, 금, 토 10:00 - 18:00 \n수, 일 10:00 - 21:00'
WHERE id = 320;

UPDATE places
SET opening_hours = '화 - 금 12:00 - 20:00 \n토, 일 12:00 - 20:30 \n매주 월 정기휴무'
WHERE id = 247;

UPDATE places
SET opening_hours = '10:00 - 17:00 \n매주 수, 목 정기휴무 \n품절 시 조기마감'
WHERE id = 40;

UPDATE places
SET opening_hours = '수, 목, 금, 일 14:00 - 19:00 \n토 14:00 - 20:00\n매주 월, 화 정기휴무'
WHERE id = 121;

UPDATE places
SET opening_hours = '매일 10:30 - 19:30'
WHERE id = 318;
