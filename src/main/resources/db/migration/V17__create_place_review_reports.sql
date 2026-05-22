-- 장소 리뷰 신고 테이블
CREATE TABLE place_review_reports
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    place_review_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    report_type     VARCHAR(50) NOT NULL,
    status          VARCHAR(50) NOT NULL,

    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_place_review_reports_review
        FOREIGN KEY (place_review_id) REFERENCES place_reviews (id) ON DELETE CASCADE,
    CONSTRAINT fk_place_review_reports_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_place_review_reports_place_review_id ON place_review_reports (place_review_id);
CREATE INDEX idx_place_review_reports_status ON place_review_reports (status);
CREATE INDEX idx_place_review_reports_created_at ON place_review_reports (created_at);

-- 동일 사용자가 같은 리뷰를 중복 신고하지 못하도록 유니크 제약
CREATE UNIQUE INDEX uq_place_review_reports_user_review
    ON place_review_reports (user_id, place_review_id);
