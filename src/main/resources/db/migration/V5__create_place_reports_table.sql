-- 장소 제보 테이블 생성
CREATE TABLE place_reports (
                               id BIGSERIAL PRIMARY KEY,
                               place_id BIGINT NOT NULL,
                               user_id BIGINT NOT NULL,
                               report_type VARCHAR(50) NOT NULL,
                               content TEXT,
                               status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                               created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                               updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                               CONSTRAINT fk_place_reports_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
                               CONSTRAINT fk_place_reports_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 장소 제보 이미지 테이블 생성 (@ElementCollection)
CREATE TABLE place_report_images (
                                     place_report_id BIGINT NOT NULL,
                                     image_key TEXT NOT NULL,

                                     CONSTRAINT fk_place_report_images_report_id FOREIGN KEY (place_report_id) REFERENCES place_reports(id) ON DELETE CASCADE
);

-- ================================
-- 인덱스 생성
-- ================================
CREATE INDEX idx_place_report_place_id ON place_reports(place_id);
CREATE INDEX idx_place_report_status ON place_reports(status);
CREATE INDEX idx_place_report_created_at ON place_reports(created_at);
CREATE INDEX idx_place_report_user_place_created ON place_reports(user_id, place_id, created_at);

-- ================================
-- 트리거 생성 (updated_at 자동 업데이트)
-- ================================
CREATE TRIGGER update_place_reports_updated_at
    BEFORE UPDATE ON place_reports
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();