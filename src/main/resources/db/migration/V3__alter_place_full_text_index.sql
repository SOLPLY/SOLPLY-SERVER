ALTER TABLE places
    ADD FULLTEXT INDEX idx_places_name_fulltext (name);