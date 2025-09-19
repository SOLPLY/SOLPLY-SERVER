CREATE TABLE place_requests (
     id BIGSERIAL PRIMARY KEY,
     place_name VARCHAR(255) NOT NULL,
     address VARCHAR(255) NOT NULL,
     reason TEXT NOT NULL,
     user_id BIGINT NOT NULL,
     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

     CONSTRAINT fk_place_request_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TRIGGER update_place_request_updated_at
    BEFORE UPDATE ON place_requests
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();


CREATE TABLE place_request_tag (
    id BIGSERIAL PRIMARY KEY,
    place_request_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,

    CONSTRAINT fk_place_request_tag_place_request FOREIGN KEY (place_request_id) REFERENCES place_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_place_request_tag_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE,
    CONSTRAINT uk_place_request_tag UNIQUE (place_request_id, tag_id)
);

CREATE INDEX idx_place_request_tag_tag_id ON place_request_tag(tag_id);


CREATE TABLE place_request_images (
    place_request_id BIGINT NOT NULL,
    image_file_key TEXT NOT NULL,
    display_order INT NOT NULL,

    CONSTRAINT fk_place_request_image
        FOREIGN KEY (place_request_id) REFERENCES place_requests(id) ON DELETE CASCADE
);

CREATE INDEX idx_place_request_image_request_id ON place_request_images(place_request_id);
CREATE INDEX idx_place_request_image_order ON place_request_images(place_request_id, display_order);