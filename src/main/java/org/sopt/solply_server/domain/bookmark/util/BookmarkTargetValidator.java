package org.sopt.solply_server.domain.bookmark.util;

import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;

public interface BookmarkTargetValidator {
    BookmarkTargetType supports();
    void validate(Long targetId);
}

