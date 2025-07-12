package org.sopt.solply_server.domain.course.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.LocalDateTime;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.CLASS,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@class"
)
public record CourseBookmarkRedisDto(
        Long userId,
        Long courseId,
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
    public static CourseBookmarkRedisDto createActive(Long userId, Long courseId) {
        return new CourseBookmarkRedisDto(userId, courseId, LocalDateTime.now(), BookmarkStatus.ACTIVE);
    }

    /**
     * 삭제 마커 생성
     */
    public static CourseBookmarkRedisDto createDeleted(Long userId, Long courseId) {
        return new CourseBookmarkRedisDto(userId, courseId, LocalDateTime.now(), BookmarkStatus.DELETED);
    }

    public static CourseBookmarkRedisDto of(Long userId, Long courseId) {
        return createActive(userId, courseId);
    }
}