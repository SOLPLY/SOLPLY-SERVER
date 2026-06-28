-- 동네별 자연어 추천 예시 문구 테이블
CREATE TABLE recommend_example_phrases
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    town_id       BIGINT       NOT NULL,
    target_type   VARCHAR(20)  NOT NULL,
    content       VARCHAR(255) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_recommend_example_phrases_town
        FOREIGN KEY (town_id) REFERENCES towns (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_phrase_town_type_active
    ON recommend_example_phrases (town_id, target_type, active);
