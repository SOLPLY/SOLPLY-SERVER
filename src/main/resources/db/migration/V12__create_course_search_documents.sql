CREATE TABLE course_search_documents
(
    course_id       BIGINT       NOT NULL,
    retrieval_text  TEXT         NOT NULL,
    embedding       MEDIUMBLOB,
    embedding_model VARCHAR(100),
    status          VARCHAR(20)  NOT NULL DEFAULT 'INIT',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (course_id),
    CONSTRAINT fk_course_search_documents_course
        FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE
);
