CREATE TABLE operation_time_slots (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 place_id BIGINT NOT NULL,

                                 day_of_week TINYINT NOT NULL COMMENT '1:월, 2:화, 3:수, 4:목, 5:금, 6:토, 7:일',
                                 is_day_off TINYINT(1) DEFAULT 0 COMMENT '0: 영업일, 1: 휴무일',

                                 start_time TIME,
                                 end_time TIME,
                                 end_next_day BOOLEAN NOT NULL DEFAULT FALSE,

                                 last_order_time TIME,
                                 description varchar(255),

                                 created_at      DATETIME(6)  NOT NULL,
                                 updated_at      DATETIME(6)  NOT NULL,

                                 CONSTRAINT fk_operation_time_place
                                     FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,

                                UNIQUE KEY uk_place_day (place_id, day_of_week, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;