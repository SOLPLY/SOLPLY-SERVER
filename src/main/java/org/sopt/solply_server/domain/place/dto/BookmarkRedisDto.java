package org.sopt.solply_server.domain.place.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

public record BookmarkRedisDto(
        Long userId,
        Long placeId,
        LocalDateTime createdAt) {

    public static  BookmarkRedisDto of(Long userId, Long placeId) {
        return new BookmarkRedisDto(userId, placeId, LocalDateTime.now());
    }
}