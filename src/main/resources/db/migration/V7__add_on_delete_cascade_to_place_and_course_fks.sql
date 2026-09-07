-- place_tag: place 삭제 시 연관 태그 매핑 삭제 (JPA 안전망)
ALTER TABLE place_tag
    DROP FOREIGN KEY fk_place_tag_place,
    ADD CONSTRAINT fk_place_tag_place_cascade
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE;

-- place_social_links: place 삭제 시 SNS 링크 삭제 (JPA 안전망)
ALTER TABLE place_social_links
    DROP FOREIGN KEY fk_place_social_links_place,
    ADD CONSTRAINT fk_place_social_links_place_cascade
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE;

-- place_images: place 삭제 시 이미지 정보 삭제 (JPA 안전망)
ALTER TABLE place_images
    DROP FOREIGN KEY fk_place_images_place,
    ADD CONSTRAINT fk_place_images_place_cascade
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE;

-- place_reports: place 삭제 시 신고 데이터 삭제
ALTER TABLE place_reports
    DROP FOREIGN KEY fk_place_reports_place,
    ADD CONSTRAINT fk_place_reports_place_cascade
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE;

-- place_reviews: place 삭제 시 리뷰 삭제
ALTER TABLE place_reviews
    DROP FOREIGN KEY fk_place_review_to_place,
    ADD CONSTRAINT fk_place_review_to_place_cascade
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE;

-- course_place: course 삭제 시 코스-장소 매핑 삭제 (JPA 안전망)
ALTER TABLE course_place
    DROP FOREIGN KEY fk_course_place_course,
    ADD CONSTRAINT fk_course_place_course_cascade
        FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE;

-- course_place: place 삭제 시 코스-장소 매핑 삭제
ALTER TABLE course_place
    DROP FOREIGN KEY fk_course_place_place,
    ADD CONSTRAINT fk_course_place_place_cascade
        FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE;
