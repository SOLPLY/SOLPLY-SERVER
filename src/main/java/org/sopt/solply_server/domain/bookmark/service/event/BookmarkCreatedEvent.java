package org.sopt.solply_server.domain.bookmark.service.event;

import java.time.LocalDateTime;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;

public record BookmarkCreatedEvent(
        Long userId,
        BookmarkTargetType type,
        Long targetId,
        LocalDateTime createdAt,
        Long townId
) {}
