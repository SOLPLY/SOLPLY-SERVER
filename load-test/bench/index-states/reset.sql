ALTER TABLE bookmarks DROP FOREIGN KEY fk_bookmarks_user;
DROP INDEX uk_bookmark_user_target ON bookmarks;
DROP INDEX idx_bookmark_user_type ON bookmarks;
DROP INDEX idx_bookmark_user_type_created_target ON bookmarks;
DROP INDEX idx_bookmark_target ON bookmarks;
