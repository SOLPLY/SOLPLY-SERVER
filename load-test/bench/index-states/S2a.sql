CREATE UNIQUE INDEX uk_bookmark_user_target ON bookmarks (user_id, target_type, target_id);
CREATE INDEX idx_bookmark_target ON bookmarks (target_type, target_id);
ALTER TABLE bookmarks ADD CONSTRAINT fk_bookmarks_user FOREIGN KEY (user_id) REFERENCES users(id);
SELECT 'S2a applied' AS state;
