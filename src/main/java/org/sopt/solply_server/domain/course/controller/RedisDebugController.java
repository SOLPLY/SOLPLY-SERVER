package org.sopt.solply_server.domain.course.controller;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.service.cache.CourseBookmarkRedisDataManager;
import org.sopt.solply_server.global.cache.CacheService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class RedisDebugController {

    private final CacheService cacheService;
    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;

    @GetMapping("/redis/course-bookmarks/{userId}")
    public ResponseEntity<Map<String, Object>> debugCourseBookmarks(@PathVariable Long userId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 사용자의 모든 코스 북마크 키 조회
            String pattern = String.format("course_bookmark:%d:*", userId);
            Set<String> keys = cacheService.findKeys(pattern);

            result.put("pattern", pattern);
            result.put("foundKeys", keys);
            result.put("keyCount", keys.size());

            // 각 키의 원시 데이터 조회
            Map<String, Object> keyData = new HashMap<>();
            for (String key : keys) {
                try {
                    Object rawData = cacheService.get(key, Object.class);
                    keyData.put(key, rawData);
                } catch (Exception e) {
                    keyData.put(key, "ERROR: " + e.getMessage());
                }
            }
            result.put("keyData", keyData);

            // 변환 가능한 데이터 조회
            List<CourseBookmarkRedisDto> activeBookmarks = courseBookmarkRedisDataManager.getActiveBookmarkDtos(userId);
            result.put("activeBookmarks", activeBookmarks);
            result.put("activeCount", activeBookmarks.size());

        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

//    @DeleteMapping("/redis/course-bookmarks/{userId}")
//    public ResponseEntity<String> clearCourseBookmarks(@PathVariable Long userId) {
//        courseBookmarkRedisDataManager.clearAllUserCourseBookmarks(userId);
//        return ResponseEntity.ok("All course bookmark data cleared for user " + userId);
//    }
}