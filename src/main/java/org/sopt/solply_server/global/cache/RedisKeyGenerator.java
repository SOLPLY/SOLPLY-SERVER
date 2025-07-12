package org.sopt.solply_server.global.cache;

public class RedisKeyGenerator {

    /**
     * Place 북마크 전용 키 생성
     */
    public static String generatePlaceBookmarkKey(Long userId, Long placeId) {
        return String.format("%s:%d:%d", CachePrefix.PLACE_BOOKMARK.getPrefix(), userId, placeId);
    }

    /**
     * Course 북마크 전용 키 생성
     */
    public static String generateCourseBookmarkKey(Long userId, Long courseId) {
        return String.format("%s:%d:%d", CachePrefix.COURSE_BOOKMARK.getPrefix(), userId, courseId);
    }

}