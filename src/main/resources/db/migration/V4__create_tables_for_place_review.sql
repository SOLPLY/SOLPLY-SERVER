-- 1. place_review 테이블 생성
CREATE TABLE place_reviews (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              user_id         BIGINT       NOT NULL,
                              place_id        BIGINT       NOT NULL,
                              visited_at      DATE         NOT NULL,
                              visit_time_slot VARCHAR(20)  NOT NULL,
                              content         VARCHAR(500) NOT NULL,
                              created_at      DATETIME(6)  NOT NULL,
                              updated_at      DATETIME(6)  NOT NULL,

    -- 외래키 제약 조건
                              CONSTRAINT fk_place_review_to_user FOREIGN KEY (user_id) REFERENCES users (id),
                              CONSTRAINT fk_place_review_to_place FOREIGN KEY (place_id) REFERENCES places (id)
);

-- 2. place_review_image 테이블
CREATE TABLE place_review_images (
                                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                    place_review_id       BIGINT       NOT NULL,
                                    image_url             VARCHAR(500) NOT NULL,

    -- 외래키 제약 조건 (CascadeType.ALL, orphanRemoval=true 대응)
                                    CONSTRAINT fk_review_image_to_review FOREIGN KEY (place_review_id)
                                        REFERENCES place_reviews (id) ON DELETE CASCADE
);

-- 장소별 최신 리뷰 조회 최적화
CREATE INDEX idx_place_reviews_place_id_created_at
    ON place_reviews (place_id, created_at DESC);

-- 내 리뷰 목록 조회 최적화
CREATE INDEX idx_place_reviews_user_id_created_at
    ON place_reviews (user_id, created_at DESC);
