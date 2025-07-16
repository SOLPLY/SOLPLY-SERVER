-- 1. Towns 테이블 생성 (자기 참조 구조)
CREATE TABLE towns (
                       id BIGSERIAL PRIMARY KEY,
                       name VARCHAR(255) NOT NULL,
                       parent_id BIGINT,

                       CONSTRAINT fk_towns_parent_id FOREIGN KEY (parent_id) REFERENCES towns(id)
);

-- 2. Users 테이블 생성
CREATE TABLE users (
                       id BIGSERIAL PRIMARY KEY,
                       nickname VARCHAR(30) UNIQUE,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       is_new_user BOOLEAN NOT NULL DEFAULT true,
                       persona VARCHAR(50), -- UserPersona enum
                       selected_town_id BIGINT
);

-- 3. Social User Info 테이블 생성 (BaseTimeEntity 상속)
CREATE TABLE social_user_info (
                                  id BIGSERIAL PRIMARY KEY,
                                  user_id BIGINT NOT NULL,
                                  social_platform VARCHAR(20) NOT NULL,
                                  social_code VARCHAR(100) NOT NULL UNIQUE,
                                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                  CONSTRAINT fk_social_user_info_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 4. User Interest Town 테이블 생성
CREATE TABLE user_town (
                           id BIGSERIAL PRIMARY KEY,
                           user_id BIGINT NOT NULL,
                           town_id BIGINT NOT NULL,

                           CONSTRAINT fk_user_town_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                           CONSTRAINT fk_user_town_town_id FOREIGN KEY (town_id) REFERENCES towns(id) ON DELETE CASCADE
);

-- 5. Tags 테이블 생성 (자기 참조 구조)
CREATE TABLE tags (
                      id BIGSERIAL PRIMARY KEY,
                      name VARCHAR(255) NOT NULL, -- TagName enum
                      type VARCHAR(50) NOT NULL,  -- TagType enum
                      parent_id BIGINT,

                      CONSTRAINT fk_tags_parent_id FOREIGN KEY (parent_id) REFERENCES tags(id)
);

-- 6. Places 테이블 생성 (BaseTimeEntity 상속)
CREATE TABLE places (
                        id BIGSERIAL PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        introduction TEXT NOT NULL,
                        address VARCHAR(500),
                        contact_number TEXT,
                        opening_hours VARCHAR(500),
                        latitude double precision,
                        longitude double precision,
                        place_default_id BIGINT NOT NULL,
                        place_type VARCHAR(100) NOT NULL,
                        town_id BIGINT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                        CONSTRAINT fk_places_town_id FOREIGN KEY (town_id) REFERENCES towns(id)
);

-- 7. Place Social Links 테이블 생성 (@ElementCollection)
CREATE TABLE place_social_links (
                                    place_id BIGINT NOT NULL,
                                    platform VARCHAR(50) NOT NULL, -- SnsPlatform enum
                                    url TEXT NOT NULL,

                                    PRIMARY KEY (place_id, platform),
                                    CONSTRAINT fk_place_social_links_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE
);

-- 8. Place Images 테이블 생성 (@ElementCollection)
CREATE TABLE place_images (
                              place_id BIGINT NOT NULL,
                              image_file_key TEXT NOT NULL,
                              display_order INTEGER,

                              CONSTRAINT fk_place_images_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE
);

-- 9. Place Tags 테이블 생성 (다대다 관계)
CREATE TABLE place_tag (
                           id BIGSERIAL PRIMARY KEY,
                           place_id BIGINT NOT NULL,
                           tag_id BIGINT NOT NULL,

                           CONSTRAINT fk_place_tag_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
                           CONSTRAINT fk_place_tag_tag_id FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);

-- 10. Place Bookmark 테이블 생성 (BaseTimeEntity 상속)
CREATE TABLE place_bookmark (
                                id BIGSERIAL PRIMARY KEY,
                                place_id BIGINT NOT NULL,
                                user_id BIGINT NOT NULL,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_place_bookmark_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
                                CONSTRAINT fk_place_bookmark_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 11. Courses 테이블 생성 (BaseTimeEntity 상속)
CREATE TABLE courses (
                         id BIGSERIAL PRIMARY KEY,
                         name VARCHAR(255) NOT NULL,
                         introduction TEXT NOT NULL,
                         is_shared BOOLEAN NOT NULL DEFAULT false,
                         town_id BIGINT NOT NULL,
                         created_by BIGINT,
                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                         CONSTRAINT fk_courses_town_id FOREIGN KEY (town_id) REFERENCES towns(id)
);

-- 12. Course Places 테이블 생성 (BaseTimeEntity 상속)
CREATE TABLE course_place (
                              id BIGSERIAL PRIMARY KEY,
                              place_order INTEGER NOT NULL,
                              course_id BIGINT NOT NULL,
                              place_id BIGINT NOT NULL,
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_course_place_course_id FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
                              CONSTRAINT fk_course_place_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE
);

-- 13. Course Bookmark 테이블 생성 (BaseTimeEntity 상속)
CREATE TABLE course_bookmark (
                                 id BIGSERIAL PRIMARY KEY,
                                 course_id BIGINT NOT NULL,
                                 user_id BIGINT NOT NULL,
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                 CONSTRAINT fk_course_bookmark_course_id FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
                                 CONSTRAINT fk_course_bookmark_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ================================
-- 인덱스 생성
-- ================================

-- Towns 테이블 인덱스
CREATE INDEX idx_town_parent_id ON towns(parent_id);

-- Social User Info 테이블 인덱스
CREATE INDEX idx_user_social_platform ON social_user_info(user_id, social_platform);

-- User Interest Town 테이블 인덱스
CREATE INDEX idx_user_interest_town_user ON user_town(user_id);

-- Tags 테이블 인덱스
CREATE INDEX idx_tag_parent_id ON tags(parent_id);
CREATE INDEX idx_tag_id_type ON tags(id, type);
CREATE INDEX idx_tag_type_parent ON tags(type, parent_id);

-- Places 테이블 인덱스
CREATE INDEX idx_places_town_id ON places(town_id);

-- Place Tags 테이블 인덱스
CREATE INDEX idx_place_tag_place_id ON place_tag(place_id);
CREATE INDEX idx_place_tag_tag_id ON place_tag(tag_id);

-- Place Bookmark 테이블 인덱스
CREATE UNIQUE INDEX idx_place_bookmark_user_place ON place_bookmark(user_id, place_id);
CREATE INDEX idx_place_bookmark_user_created ON place_bookmark(user_id, created_at);

-- Place Images 테이블 인덱스 (정렬용)
CREATE INDEX idx_place_images_place_order ON place_images(place_id, display_order);

-- Courses 테이블 인덱스
CREATE INDEX idx_course_town_id ON courses(town_id);
CREATE INDEX idx_course_is_shared ON courses(is_shared);
CREATE INDEX idx_course_created_by ON courses(created_by);

-- Course Place 테이블 인덱스
CREATE UNIQUE INDEX idx_course_place_course_id_place_id ON course_place(course_id, place_id);
CREATE INDEX idx_course_place_place_id ON course_place(place_id);

-- Course Bookmark 테이블 인덱스
CREATE UNIQUE INDEX idx_course_bookmark_user_course ON course_bookmark(user_id, course_id);

-- ================================
-- 유니크 제약조건 생성
-- ================================

-- User Interest Town 중복 방지
ALTER TABLE user_town ADD CONSTRAINT uk_user_town_user_town UNIQUE (user_id, town_id);

-- Place Tag 중복 방지
ALTER TABLE place_tag ADD CONSTRAINT uk_place_tag_place_tag UNIQUE (place_id, tag_id);

-- Place Bookmark 중복 방지
ALTER TABLE place_bookmark ADD CONSTRAINT uk_place_bookmark_user_place UNIQUE (user_id, place_id);

-- Social User Info 유니크 제약조건
ALTER TABLE social_user_info ADD CONSTRAINT uk_social_user_info_social_code UNIQUE (social_code);

-- ================================
-- 트리거 생성 (updated_at 자동 업데이트)
-- ================================

-- updated_at 자동 업데이트 함수
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- BaseTimeEntity를 상속받는 테이블들에 트리거 적용
CREATE TRIGGER update_social_user_info_updated_at BEFORE UPDATE ON social_user_info FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_places_updated_at BEFORE UPDATE ON places FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_place_bookmark_updated_at BEFORE UPDATE ON place_bookmark FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_courses_updated_at BEFORE UPDATE ON courses FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_course_place_updated_at BEFORE UPDATE ON course_place FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_course_bookmark_updated_at BEFORE UPDATE ON course_bookmark FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();