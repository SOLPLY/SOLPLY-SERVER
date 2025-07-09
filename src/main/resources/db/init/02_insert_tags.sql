-- MAIN 태그
INSERT INTO tags (id, name, type, parent_id) VALUES
                                                 (1, 'CAFE', 'MAIN', NULL),
                                                 (2, 'FOOD', 'MAIN', NULL),
                                                 (3, 'SHOPPING', 'MAIN', NULL),
                                                 (4, 'BOOKSTORE', 'MAIN', NULL),
                                                 (5, 'UNIQUE_SPACE', 'MAIN', NULL),
                                                 (6, 'WALKING', 'MAIN', NULL);

-- CAFE 옵션 1, 2
INSERT INTO tags (id, name, type, parent_id) VALUES
                                                 (7, 'COFFEE_DESSERT', 'OPTION1', 1),
                                                 (8, 'WORK', 'OPTION1', 1),
                                                 (9, 'READING', 'OPTION1', 1),
                                                 (10, 'HEALING', 'OPTION1', 1),
                                                 (11, 'SIGNATURE_MENU', 'OPTION2', 1),
                                                 (12, 'MOOD_INTERIOR', 'OPTION2', 1),
                                                 (13, 'SUNLIGHT', 'OPTION2', 1),
                                                 (14, 'MANY_PLUG', 'OPTION2', 1),
                                                 (15, 'NO_TIME_LIMIT', 'OPTION2', 1),
                                                 (16, 'BAR_TABLE', 'OPTION2', 1);

-- FOOD 옵션 1, 2
INSERT INTO tags (id, name, type, parent_id) VALUES
                                                 (17, 'KOREAN_FOOD', 'OPTION1', 2),
                                                 (18, 'CHINESE_FOOD', 'OPTION1', 2),
                                                 (19, 'JAPANESE_FOOD', 'OPTION1', 2),
                                                 (20, 'WESTERN_FOOD', 'OPTION1', 2),
                                                 (21, 'BAR', 'OPTION1', 2),
                                                 (22, 'BAKERY', 'OPTION1', 2),
                                                 (23, 'ASIAN_FOOD', 'OPTION1', 2),
                                                 (24, 'SINGLE_MENU', 'OPTION2', 2),
                                                 (25, 'SELF_SERVICE', 'OPTION2', 2);

-- UNIQUE_SPACE & SHOPPING 옵션
INSERT INTO tags (id, name, type, parent_id) VALUES
                                                 (26, 'ART', 'OPTION1', 5),
                                                 (27, 'WORKSHOP', 'OPTION1', 5),
                                                 (28, 'LIFESTYLE_SHOP', 'OPTION1', 3),
                                                 (29, 'VINTAGE_SHOP', 'OPTION1', 3),
                                                 (30, 'POPUP_MARKET', 'OPTION1', 3);