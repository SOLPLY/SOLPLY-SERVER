-- 자연어 추천 예시 문구 테이블 (동네 무관 고정 목록)
CREATE TABLE recommend_example_phrases
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type   VARCHAR(20)  NOT NULL,
    content       VARCHAR(255) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,

    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_phrase_type
    ON recommend_example_phrases (target_type, display_order);
