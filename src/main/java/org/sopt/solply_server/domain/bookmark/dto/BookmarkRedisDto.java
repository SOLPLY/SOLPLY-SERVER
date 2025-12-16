package org.sopt.solply_server.domain.bookmark.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;

public record BookmarkRedisDto(
        Long userId,
        BookmarkTargetType targetType,
        Long targetId,
        LocalDateTime createdAt,
        BookmarkStatus status
) {
    public enum BookmarkStatus { ACTIVE, DELETED }

    @JsonIgnore
    public boolean isActive() { return status == BookmarkStatus.ACTIVE; }

    public static BookmarkRedisDto active(Long userId, BookmarkTargetType type, Long targetId) {
        return new BookmarkRedisDto(userId, type, targetId, LocalDateTime.now(), BookmarkStatus.ACTIVE);
    }

    public static BookmarkRedisDto deleted(Long userId, BookmarkTargetType type, Long targetId) {
        return new BookmarkRedisDto(userId, type, targetId, LocalDateTime.now(), BookmarkStatus.DELETED);
    }
}