-- 1. place_search_document 테이블
CREATE TABLE place_search_documents (
    place_id        BIGINT       NOT NULL,
    retrieval_text  TEXT         NOT NULL,
    embedding       MEDIUMBLOB,
    embedding_model VARCHAR(100),
    generated_at    DATETIME(6),

    PRIMARY KEY (place_id),
    CONSTRAINT fk_place_search_document_place FOREIGN KEY (place_id) REFERENCES places (id)
);

-- 2. place_review_summary 테이블
CREATE TABLE place_review_summary (
    place_id               BIGINT  NOT NULL,
    summary_content        TEXT,
    review_count_at_time   INT     NOT NULL DEFAULT 0,
    updated_at             DATETIME(6),

    PRIMARY KEY (place_id),
    CONSTRAINT fk_place_review_summary_place FOREIGN KEY (place_id) REFERENCES places (id)
);
