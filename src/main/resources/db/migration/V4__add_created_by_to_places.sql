DELETE FROM users;

ALTER TABLE users AUTO_INCREMENT = 1;

INSERT INTO users (id, nickname, email, is_new_user, persona, selected_town_id)
    VALUES (1, 'admin', 'admin@example.com', false, null, NULL);

ALTER TABLE places ADD COLUMN created_by BIGINT NOT NULL DEFAULT 1;

UPDATE places SET created_by = 1;

ALTER TABLE places ADD CONSTRAINT fk_places_created_by FOREIGN KEY (created_by) REFERENCES users(id);