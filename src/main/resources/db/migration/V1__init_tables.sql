-- 1. Towns 테이블 생성 (자기 참조 구조)
CREATE TABLE towns (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                       name VARCHAR(255) NOT NULL,
                       parent_id BIGINT,
                       CONSTRAINT fk_towns_parent_id FOREIGN KEY (parent_id) REFERENCES towns(id)
);

-- 2. Users 테이블 생성
CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                       nickname VARCHAR(30) UNIQUE,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       is_new_user BOOLEAN NOT NULL DEFAULT true,
                       persona VARCHAR(50),
                       selected_town_id BIGINT
);

-- 3. Social User Info
CREATE TABLE social_user_info (
                                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                  user_id BIGINT NOT NULL,
                                  social_platform VARCHAR(20) NOT NULL,
                                  social_code VARCHAR(100) NOT NULL UNIQUE,
                                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                  CONSTRAINT fk_social_user_info_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 4. User Interest Town
CREATE TABLE user_town (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY,
                           user_id BIGINT NOT NULL,
                           town_id BIGINT NOT NULL,
                           CONSTRAINT fk_user_town_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                           CONSTRAINT fk_user_town_town_id FOREIGN KEY (town_id) REFERENCES towns(id) ON DELETE CASCADE,
                           CONSTRAINT uk_user_town_user_town UNIQUE (user_id, town_id)
);

-- 5. Tags
CREATE TABLE tags (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      name VARCHAR(255) NOT NULL,
                      type VARCHAR(50) NOT NULL,
                      parent_id BIGINT,
                      CONSTRAINT fk_tags_parent_id FOREIGN KEY (parent_id) REFERENCES tags(id)
);

-- 6. Places
CREATE TABLE places (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        introduction TEXT NOT NULL,
                        address VARCHAR(500),
                        contact_number TEXT,
                        opening_hours VARCHAR(500),
                        latitude DOUBLE,
                        longitude DOUBLE,
                        place_default_id BIGINT NOT NULL,
                        place_type VARCHAR(100) NOT NULL,
                        town_id BIGINT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        CONSTRAINT fk_places_town_id FOREIGN KEY (town_id) REFERENCES towns(id)
);

-- 7. Place Social Links
CREATE TABLE place_social_links (
                                    place_id BIGINT NOT NULL,
                                    platform VARCHAR(50) NOT NULL,
                                    url TEXT NOT NULL,
                                    PRIMARY KEY (place_id, platform),
                                    CONSTRAINT fk_place_social_links_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE
);

-- 8. Place Images
CREATE TABLE place_images (
                              place_id BIGINT NOT NULL,
                              image_file_key TEXT NOT NULL,
                              display_order INT,
                              CONSTRAINT fk_place_images_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE
);

-- 9. Place Tags
CREATE TABLE place_tag (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY,
                           place_id BIGINT NOT NULL,
                           tag_id BIGINT NOT NULL,
                           CONSTRAINT fk_place_tag_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
                           CONSTRAINT fk_place_tag_tag_id FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE,
                           CONSTRAINT uk_place_tag UNIQUE (place_id, tag_id)
);

-- 10. Place Bookmark
CREATE TABLE place_bookmark (
                                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                place_id BIGINT NOT NULL,
                                user_id BIGINT NOT NULL,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                CONSTRAINT fk_place_bookmark_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
                                CONSTRAINT fk_place_bookmark_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                                CONSTRAINT uk_place_bookmark_user_place UNIQUE (user_id, place_id)
);

-- 11. Courses
CREATE TABLE courses (
                         id BIGINT AUTO_INCREMENT PRIMARY KEY,
                         name VARCHAR(255) NOT NULL,
                         introduction TEXT NOT NULL,
                         is_shared BOOLEAN NOT NULL DEFAULT false,
                         town_id BIGINT NOT NULL,
                         created_by BIGINT,
                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                         CONSTRAINT fk_courses_town_id FOREIGN KEY (town_id) REFERENCES towns(id)
);

-- 12. Course Places
CREATE TABLE course_place (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              place_order INT NOT NULL,
                              course_id BIGINT NOT NULL,
                              place_id BIGINT NOT NULL,
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                              CONSTRAINT fk_course_place_course_id FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
                              CONSTRAINT fk_course_place_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
                              CONSTRAINT uk_course_place UNIQUE (course_id, place_id)
);

-- 13. Course Bookmark
CREATE TABLE course_bookmark (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 course_id BIGINT NOT NULL,
                                 user_id BIGINT NOT NULL,
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                 CONSTRAINT fk_course_bookmark_course_id FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
                                 CONSTRAINT fk_course_bookmark_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                                 CONSTRAINT uk_course_bookmark UNIQUE (user_id, course_id)
);

-- 14. Place Requests
CREATE TABLE place_requests (
                                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                place_name VARCHAR(255) NOT NULL,
                                address VARCHAR(255) NOT NULL,
                                reason TEXT NOT NULL,
                                user_id BIGINT NOT NULL,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                CONSTRAINT fk_place_request_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 15. Place Request Tags
CREATE TABLE place_request_tag (
                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   place_request_id BIGINT NOT NULL,
                                   tag_id BIGINT NOT NULL,
                                   CONSTRAINT fk_place_request_tag_place_request FOREIGN KEY (place_request_id) REFERENCES place_requests (id) ON DELETE CASCADE,
                                   CONSTRAINT fk_place_request_tag_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE,
                                   CONSTRAINT uk_place_request_tag UNIQUE (place_request_id, tag_id)
);

-- 16. Place Request Images
CREATE TABLE place_request_images (
                                      place_request_id BIGINT NOT NULL,
                                      image_file_key TEXT NOT NULL,
                                      display_order INT NOT NULL,
                                      CONSTRAINT fk_place_request_image FOREIGN KEY (place_request_id) REFERENCES place_requests(id) ON DELETE CASCADE
);

-- 17. Place Reports
CREATE TABLE place_reports (
                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                               place_id BIGINT NOT NULL,
                               user_id BIGINT NOT NULL,
                               report_type VARCHAR(50) NOT NULL,
                               content TEXT,
                               status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                               created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                               updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                               CONSTRAINT fk_place_reports_place_id FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE CASCADE,
                               CONSTRAINT fk_place_reports_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 18. Place Report Images
CREATE TABLE place_report_images (
                                     place_report_id BIGINT NOT NULL,
                                     image_key TEXT NOT NULL,
                                     CONSTRAINT fk_place_report_images_report_id FOREIGN KEY (place_report_id) REFERENCES place_reports(id) ON DELETE CASCADE
);

-- ================================
-- 인덱스
-- ================================
CREATE INDEX idx_town_parent_id ON towns(parent_id);
CREATE INDEX idx_user_social_platform ON social_user_info(user_id, social_platform);
CREATE INDEX idx_user_interest_town_user ON user_town(user_id);
CREATE INDEX idx_tag_parent_id ON tags(parent_id);
CREATE INDEX idx_tag_id_type ON tags(id, type);
CREATE INDEX idx_tag_type_parent ON tags(type, parent_id);
CREATE INDEX idx_places_town_id ON places(town_id);
CREATE INDEX idx_place_tag_place_id ON place_tag(place_id);
CREATE INDEX idx_place_tag_tag_id ON place_tag(tag_id);
CREATE INDEX idx_place_bookmark_user_created ON place_bookmark(user_id, created_at);
CREATE INDEX idx_place_images_place_order ON place_images(place_id, display_order);
CREATE INDEX idx_course_town_id ON courses(town_id);
CREATE INDEX idx_course_is_shared ON courses(is_shared);
CREATE INDEX idx_course_created_by ON courses(created_by);
CREATE INDEX idx_course_place_place_id ON course_place(place_id);
CREATE INDEX idx_course_place_order ON course_place(course_id, place_order);
CREATE INDEX idx_place_request_tag_tag_id ON place_request_tag(tag_id);
CREATE INDEX idx_place_request_image_request_id ON place_request_images(place_request_id);
CREATE INDEX idx_place_request_image_order ON place_request_images(place_request_id, display_order);
CREATE INDEX idx_place_report_place_id ON place_reports(place_id);
CREATE INDEX idx_place_report_status ON place_reports(status);
CREATE INDEX idx_place_report_created_at ON place_reports(created_at);
CREATE INDEX idx_place_report_user_place_created ON place_reports(user_id, place_id, created_at);