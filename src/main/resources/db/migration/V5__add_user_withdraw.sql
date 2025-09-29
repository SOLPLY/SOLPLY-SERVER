ALTER TABLE users
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMP NULL AFTER is_deleted;

ALTER TABLE social_user_info ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE user_withdraws (
                                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                user_id BIGINT NOT NULL,
                                reason VARCHAR(32) NOT NULL,
                                reason_text VARCHAR(1000) NULL,
                                user_agent VARCHAR(255) NULL,
                                ip_address VARCHAR(45) NULL,
                                withdrawn_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                CONSTRAINT fk_user_withdraws_user
                                    FOREIGN KEY (user_id) REFERENCES users(id)
                                        ON DELETE CASCADE
);