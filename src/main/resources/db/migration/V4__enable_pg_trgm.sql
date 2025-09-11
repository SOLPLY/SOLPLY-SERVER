-- pg_trgm 확장 설치
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_places_name_trgm
    ON places USING gin (name gin_trgm_ops);