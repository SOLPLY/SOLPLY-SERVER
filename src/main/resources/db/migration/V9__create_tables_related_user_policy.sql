CREATE TABLE user_policies (
                               id BIGINT AUTO_INCREMENT PRIMARY KEY,
                               policy_type VARCHAR(50) NOT NULL,
                               version VARCHAR(50),
                               title VARCHAR(255) NOT NULL,
                               content LONGTEXT,
                               required BOOLEAN NOT NULL DEFAULT FALSE,
                               active BOOLEAN NOT NULL DEFAULT TRUE
);


CREATE TABLE user_policy_agreements (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        user_id BIGINT NOT NULL,
                                        user_policy_id BIGINT NOT NULL,
                                        is_agree BOOLEAN NOT NULL DEFAULT FALSE,
                                        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                                        CONSTRAINT uk_user_policy UNIQUE (user_id, user_policy_id),
                                        CONSTRAINT fk_user_policy_agreements_user FOREIGN KEY (user_id) REFERENCES users(id),
                                        CONSTRAINT fk_user_policy_agreements_policy FOREIGN KEY (user_policy_id) REFERENCES user_policies(id)
);