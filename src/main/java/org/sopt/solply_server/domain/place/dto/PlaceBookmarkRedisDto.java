package org.sopt.solply_server.domain.place.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.LocalDateTime;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.CLASS,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@class"
)
public record PlaceBookmarkRedisDto(
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
    public static PlaceBookmarkRedisDto createActive(Long userId, Long placeId) {
        return new PlaceBookmarkRedisDto(userId, placeId, LocalDateTime.now(), BookmarkStatus.ACTIVE);
    }

    /**
     * 삭제 마커 생성
     */
    public static PlaceBookmarkRedisDto createDeleted(Long userId, Long placeId) {
        return new PlaceBookmarkRedisDto(userId, placeId, LocalDateTime.now(), BookmarkStatus.DELETED);
    }

    public static PlaceBookmarkRedisDto of(Long userId, Long placeId) {
        return createActive(userId, placeId);
    }
}