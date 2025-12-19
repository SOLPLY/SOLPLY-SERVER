package org.sopt.solply_server.domain.bookmark.service.event;

import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;

public record BookmarkDeletedEvent(
        Long userId,
        BookmarkTargetType type,
        Long targetId
) {}