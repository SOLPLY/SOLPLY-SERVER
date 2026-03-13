-- place_search_documents: BaseTimeEntity 상속 적용 및 EmbeddingStatus 추가
ALTER TABLE place_search_documents
    DROP COLUMN generated_at,
    ADD COLUMN status      VARCHAR(20)  NOT NULL DEFAULT 'INIT',
    ADD COLUMN created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

-- place_review_summaries: BaseTimeEntity 상속 적용
ALTER TABLE place_review_summaries
    CHANGE COLUMN updated_at updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
