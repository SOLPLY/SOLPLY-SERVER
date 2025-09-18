-- flyway:transactional=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_places_name_trgm
    ON places USING gin (name gin_trgm_ops);