package org.sopt.solply_server.domain.place.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.LocalDateTime;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.CLASS,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@class"
)
public record BookmarkRedisDto(
        Long userId,
        Long placeId,
        LocalDateTime createdAt,
        BookmarkStatus status
) {

    public enum BookmarkStatus {
        ACTIVE,    // 활성 북마크
        DELETED    // 삭제 마커
    }

    @JsonIgnore
    public boolean isActive() {
        return status == BookmarkStatus.ACTIVE;
    }

    @JsonIgnore
    public boolean isDeleted() {
        return status == BookmarkStatus.DELETED;
    }

    /**
     * 활성 북마크 생성
     */
    public static BookmarkRedisDto createActive(Long userId, Long placeId) {
        return new BookmarkRedisDto(userId, placeId, LocalDateTime.now(), BookmarkStatus.ACTIVE);
    }

    /**
     * 삭제 마커 생성
     */
    public static BookmarkRedisDto createDeleted(Long userId, Long placeId) {
        return new BookmarkRedisDto(userId, placeId, LocalDateTime.now(), BookmarkStatus.DELETED);
    }

    public static BookmarkRedisDto of(Long userId, Long placeId) {
        return createActive(userId, placeId);
    }
}