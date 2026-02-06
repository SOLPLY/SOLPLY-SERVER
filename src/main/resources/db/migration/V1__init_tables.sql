-- V1__init.sql
-- MySQL 8.x / InnoDB / utf8mb4 가정

SET NAMES utf8mb4;
SET time_zone = '+09:00';

-- =========================
-- 1) users
-- =========================
CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                       role VARCHAR(20) NOT NULL,
                       nickname VARCHAR(30) UNIQUE,
                       email VARCHAR(255) UNIQUE,
                       profile_image_file_key TEXT,
                       is_new_user BOOLEAN NOT NULL DEFAULT TRUE,
                       persona VARCHAR(50), -- UserPersona (EnumType.STRING)
                       is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
                       deleted_at DATETIME NULL,
                       selected_town_id BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =========================
-- 2) social_user_info  (SocialUserInfo)
-- =========================
CREATE TABLE social_user_info (
                                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                  user_id BIGINT NOT NULL,
                                  social_platform VARCHAR(20) NOT NULL,  -- SocialPlatform
                                  social_code VARCHAR(100) NOT NULL,
                                  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
                                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                  CONSTRAINT fk_social_user_info_user
                                      FOREIGN KEY (user_id) REFERENCES users(id),

                                  CONSTRAINT uk_social_user_info_social_code
                                      UNIQUE (social_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_user_social_platform
    ON social_user_info(user_id, social_platform);


-- =========================
-- 3) towns
-- =========================
CREATE TABLE towns (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                       name VARCHAR(255) NOT NULL,
                       parent_id BIGINT NULL,
                       active BOOLEAN NOT NULL,

                       CONSTRAINT fk_towns_parent
                           FOREIGN KEY (parent_id) REFERENCES towns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_town_parent_id ON towns(parent_id);


-- =========================
-- 4) user_town (UserInterestTown)
-- =========================
CREATE TABLE user_town (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY,
                           user_id BIGINT NOT NULL,
                           town_id BIGINT NOT NULL,

                           CONSTRAINT fk_user_town_user
                               FOREIGN KEY (user_id) REFERENCES users(id),

                           CONSTRAINT fk_user_town_town
                               FOREIGN KEY (town_id) REFERENCES towns(id),

                           CONSTRAINT uk_user_town_user_town
                               UNIQUE (user_id, town_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_user_interest_town_user ON user_town(user_id);


-- =========================
-- 5) tags
-- =========================
CREATE TABLE tags (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      name VARCHAR(255) NOT NULL,
                      type VARCHAR(50) NOT NULL,
                      parent_id BIGINT NULL,
                      active BOOLEAN NOT NULL DEFAULT TRUE,
                      tag_usage VARCHAR(20) NOT NULL DEFAULT 'PLACE',

                      CONSTRAINT fk_tags_parent
                          FOREIGN KEY (parent_id) REFERENCES tags(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_tag_parent_id ON tags(parent_id);
CREATE INDEX idx_tag_id_type ON tags(id, type);
CREATE INDEX idx_tag_type_parent ON tags(type, parent_id);
CREATE INDEX idx_tag_usage_type_parent ON tags(tag_usage, type, parent_id);


-- =========================
-- X) tag_persona_mappings (TagPersonaMapping)
-- =========================
CREATE TABLE tag_persona_mappings (
                                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                      tag_id BIGINT NOT NULL,
                                      persona VARCHAR(30) NOT NULL,   -- UserPersona (EnumType.STRING)
                                      weight INT NOT NULL DEFAULT 1,

                                      CONSTRAINT fk_tpm_tag
                                          FOREIGN KEY (tag_id) REFERENCES tags(id),

                                      CONSTRAINT uk_tag_persona
                                          UNIQUE (tag_id, persona)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_tpm_persona ON tag_persona_mappings(persona);
CREATE INDEX idx_tpm_tag ON tag_persona_mappings(tag_id);
CREATE INDEX idx_tpm_persona_weight ON tag_persona_mappings(persona, weight);


-- =========================
-- 6) places
-- =========================
CREATE TABLE places (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        introduction VARCHAR(255) NOT NULL,
                        address VARCHAR(255) NULL,
                        contact_number TEXT NULL,
                        opening_hours VARCHAR(255) NULL,
                        latitude DOUBLE NULL,
                        longitude DOUBLE NULL,
                        place_default_id BIGINT NOT NULL,
                        place_type VARCHAR(255) NOT NULL,

                        town_id BIGINT NOT NULL,
                        created_by BIGINT NOT NULL,
                        active BOOLEAN NOT NULL ,

                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                        CONSTRAINT fk_places_town
                            FOREIGN KEY (town_id) REFERENCES towns(id),

                        CONSTRAINT fk_places_created_by
                            FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_places_town_id ON places(town_id);
CREATE INDEX idx_places_created_by_created_at ON places(created_by, created_at);
-- fulltext search (for MATCH(name) AGAINST ...)
CREATE FULLTEXT INDEX ft_places_name ON places(name);


-- =========================
-- 7) place_tag (PlaceTag)
-- =========================
CREATE TABLE place_tag (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY,
                           place_id BIGINT NOT NULL,
                           tag_id BIGINT NOT NULL,

                           CONSTRAINT fk_place_tag_place
                               FOREIGN KEY (place_id) REFERENCES places(id),

                           CONSTRAINT fk_place_tag_tag
                               FOREIGN KEY (tag_id) REFERENCES tags(id),

                           CONSTRAINT uk_place_tag_place_tag
                               UNIQUE (place_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_place_tag_place_id ON place_tag(place_id);
CREATE INDEX idx_place_tag_tag_id ON place_tag(tag_id);


-- =========================
-- 8) place_social_links (ElementCollection Map<SnsPlatform, String>)
-- =========================
CREATE TABLE place_social_links (
                                    place_id BIGINT NOT NULL,
                                    platform VARCHAR(50) NOT NULL,  -- SnsPlatform
                                    url TEXT NULL,

                                    PRIMARY KEY (place_id, platform),

                                    CONSTRAINT fk_place_social_links_place
                                        FOREIGN KEY (place_id) REFERENCES places(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =========================
-- 9) place_images (ElementCollection List<PlaceImageInfo>)
-- =========================
CREATE TABLE place_images (
                              place_id BIGINT NOT NULL,
                              image_file_key TEXT NOT NULL,
                              display_order INT NULL,

    -- 한 place 안에서 display_order 중복을 막고 싶으면 UNIQUE(place_id, display_order)도 고려 가능
                              CONSTRAINT fk_place_images_place
                                  FOREIGN KEY (place_id) REFERENCES places(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_place_images_place_id_order ON place_images(place_id, display_order);


-- =========================
-- 10) place_reports (PlaceReport)
-- =========================
CREATE TABLE place_reports (
                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                               place_id BIGINT NOT NULL,
                               user_id BIGINT NOT NULL,
                               report_type VARCHAR(50) NOT NULL, -- PlaceReportType
                               content TEXT NULL,
                               status VARCHAR(50) NOT NULL,      -- PlaceReportStatus

                               created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                               CONSTRAINT fk_place_reports_place
                                   FOREIGN KEY (place_id) REFERENCES places(id),

                               CONSTRAINT fk_place_reports_user
                                   FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_place_report_place_id ON place_reports(place_id);
CREATE INDEX idx_place_report_status ON place_reports(status);
CREATE INDEX idx_place_report_created_at ON place_reports(created_at);


-- =========================
-- 11) place_report_images (ElementCollection List<String>)
-- =========================
CREATE TABLE place_report_images (
                                     place_report_id BIGINT NOT NULL,
                                     image_key TEXT NOT NULL,

                                     CONSTRAINT fk_place_report_images_report
                                         FOREIGN KEY (place_report_id) REFERENCES place_reports(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_place_report_images_report_id ON place_report_images(place_report_id);


-- =========================
-- 12) place_requests (PlaceRequest)
-- =========================
CREATE TABLE place_requests (
                                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                place_name VARCHAR(255) NOT NULL,
                                address VARCHAR(255) NOT NULL,
                                user_id BIGINT NOT NULL,
                                reason TEXT NOT NULL,
                                status VARCHAR(50) NOT NULL,

                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                CONSTRAINT fk_place_requests_user
                                    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =========================
-- 13) place_request_images (ElementCollection List<PlaceRequestImageInfo>)
-- =========================
CREATE TABLE place_request_images (
                                      place_request_id BIGINT NOT NULL,
                                      image_file_key TEXT NOT NULL,
                                      display_order INT NOT NULL,

                                      CONSTRAINT fk_place_request_images_request
                                          FOREIGN KEY (place_request_id) REFERENCES place_requests(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_place_request_images_request_order
    ON place_request_images(place_request_id, display_order);


-- =========================
-- 14) place_request_tag (PlaceRequestTag)
-- =========================
CREATE TABLE place_request_tag (
                                   id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   place_request_id BIGINT NOT NULL,
                                   tag_id BIGINT NOT NULL,

                                   CONSTRAINT fk_place_request_tag_request
                                       FOREIGN KEY (place_request_id) REFERENCES place_requests(id),

                                   CONSTRAINT fk_place_request_tag_tag
                                       FOREIGN KEY (tag_id) REFERENCES tags(id),

                                   CONSTRAINT uk_place_request_tag_place_tag
                                       UNIQUE (place_request_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_place_request_tag_tag_id ON place_request_tag(tag_id);


-- =========================
-- 15) courses (Course)
-- =========================
CREATE TABLE courses (
                         id BIGINT AUTO_INCREMENT PRIMARY KEY,
                         name VARCHAR(255) NOT NULL,
                         introduction VARCHAR(255) NOT NULL,
                         is_shared BOOLEAN NOT NULL DEFAULT FALSE,
                         town_id BIGINT NOT NULL,
                         created_by BIGINT NULL,

                         course_tag_id BIGINT NULL,

                         active BOOLEAN NOT NULL,

                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                         CONSTRAINT fk_courses_town
                             FOREIGN KEY (town_id) REFERENCES towns(id),

                         CONSTRAINT fk_courses_created_by
                             FOREIGN KEY (created_by) REFERENCES users(id),

                         CONSTRAINT fk_courses_course_tag
                             FOREIGN KEY (course_tag_id) REFERENCES tags(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_course_town_id ON courses(town_id);
CREATE INDEX idx_course_is_shared ON courses(is_shared);
CREATE INDEX idx_course_created_by ON courses(created_by);

CREATE INDEX idx_course_course_tag_id ON courses(course_tag_id);


-- =========================
-- 16) course_place (CoursePlace)
-- =========================
CREATE TABLE course_place (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              course_id BIGINT NOT NULL,
                              place_id BIGINT NOT NULL,
                              place_order INT NOT NULL,

                              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                              CONSTRAINT fk_course_place_course
                                  FOREIGN KEY (course_id) REFERENCES courses(id),

                              CONSTRAINT fk_course_place_place
                                  FOREIGN KEY (place_id) REFERENCES places(id),

                              CONSTRAINT uk_course_place_course_place
                                  UNIQUE (course_id, place_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_course_place_order ON course_place(course_id, place_order);
CREATE INDEX idx_course_place_place_id ON course_place(place_id);


-- =========================
-- 17) bookmarks (Bookmark)
-- =========================
CREATE TABLE bookmarks (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY,
                           user_id BIGINT NOT NULL,
                           target_type VARCHAR(30) NOT NULL, -- BookmarkTargetType
                           target_id BIGINT NOT NULL,

                           created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                           CONSTRAINT fk_bookmarks_user
                               FOREIGN KEY (user_id) REFERENCES users(id),

                           CONSTRAINT uk_bookmark_user_target
                               UNIQUE (user_id, target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_bookmark_target ON bookmarks(target_type, target_id);
CREATE INDEX idx_bookmark_user_created ON bookmarks(user_id, created_at);


-- =========================
-- 18) user_policies (UserPolicy)
-- =========================
CREATE TABLE user_policies (
                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                               policy_type VARCHAR(50) NOT NULL, -- PolicyType
                               version VARCHAR(50) NULL,
                               title VARCHAR(255) NOT NULL,
                               content LONGTEXT NULL,
                               required BOOLEAN NOT NULL DEFAULT FALSE,
                               active BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =========================
-- 19) user_policy_agreements (UserPolicyAgreement)
-- =========================
CREATE TABLE user_policy_agreements (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        user_id BIGINT NOT NULL,
                                        user_policy_id BIGINT NOT NULL,
                                        is_agree BOOLEAN NOT NULL,

                                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                        CONSTRAINT fk_user_policy_agreements_user
                                            FOREIGN KEY (user_id) REFERENCES users(id),

                                        CONSTRAINT fk_user_policy_agreements_policy
                                            FOREIGN KEY (user_policy_id) REFERENCES user_policies(id),

                                        CONSTRAINT uk_user_policy
                                            UNIQUE (user_id, user_policy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =========================
-- 20) user_withdraws (UserWithdraw)
-- =========================
CREATE TABLE user_withdraws (
                                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                user_id BIGINT NOT NULL,
                                reason VARCHAR(50) NOT NULL, -- WithdrawReason
                                reason_text VARCHAR(255) NULL,
                                withdrawn_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_user_withdraws_user
                                    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;