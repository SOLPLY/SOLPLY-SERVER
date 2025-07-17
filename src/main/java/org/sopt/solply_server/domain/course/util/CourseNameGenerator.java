package org.sopt.solply_server.domain.course.util;

import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkRedisDto;
import org.sopt.solply_server.domain.course.dto.response.CourseBookmarkListGetResponse;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.service.cache.CourseBookmarkRedisDataManager;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseNameGenerator {

    private final CourseRepository courseRepository;
    private final CourseBookmarkRedisDataManager courseBookmarkRedisDataManager;

    /**
     * 사용자별 고유한 코스명 생성
     */
    public String generateUniqueNameForUser(String baseName, Long userId) {
        log.debug("사용자별 코스명 생성 시작 - baseName: '{}', userId: {}", baseName, userId);

        // 해당 사용자가 북마크한 것들의 코스명 리스트 조회
        List<CourseBookmarkRedisDto> activeBookmarks = courseBookmarkRedisDataManager.getActiveCourseBookmarks(userId);
        List<Long> courseIds = activeBookmarks.stream()
                .map(CourseBookmarkRedisDto::courseId)
                .toList();

        List<String> existingNames =
                courseRepository.findCourseNamesByBookmarkedCourses(courseIds, baseName + "%");

        if (existingNames.isEmpty()) {
            log.debug("기존 코스명이 없어 원본 이름 사용: '{}'", baseName);
            return baseName;
        }

        // 중복되지 않는 이름 생성
        String uniqueName = generateUniqueSequenceName(baseName, existingNames);
        log.debug("고유 코스명 생성 완료: '{}' -> '{}'", baseName, uniqueName);
        return uniqueName;
    }

    /**
     * 순번을 붙여서 고유한 이름 생성
     */
    private String generateUniqueSequenceName(String baseName, List<String> existingNames) {
        // 기본 이름이 중복되지 않으면 그대로 반환
        if (!existingNames.contains(baseName)) {
            return baseName;
        }

        // 다음 시퀀스 번호 찾기
        int nextSequence = findNextSequenceNumber(baseName, existingNames);
        return formatNameWithSequence(baseName, nextSequence);
    }

    /**
     * 다음 시퀀스 번호 찾기
     */
    private int findNextSequenceNumber(String baseName, List<String> existingNames) {
        Set<Integer> usedNumbers = new HashSet<>();
        String basePattern = baseName + " (";

        for (String existingName : existingNames) {
            if (existingName.startsWith(basePattern) && existingName.endsWith(")")) {
                // "홍대 맛집 투어 (1)" -> "1" 추출
                String numberPart = existingName.substring(
                        basePattern.length(),
                        existingName.length() - 1
                );

                try {
                    int number = Integer.parseInt(numberPart);
                    usedNumbers.add(number);
                } catch (NumberFormatException e) {
                    log.debug("숫자가 아닌 패턴 무시: '{}'", existingName);
                }
            }
        }

        // 다음 사용 가능한 번호 찾기
        int nextNumber = 1;
        while (usedNumbers.contains(nextNumber)) {
            nextNumber++;
        }

        return nextNumber;
    }

    /**
     * 시퀀스 번호를 붙인 이름 포맷
     */
    private String formatNameWithSequence(String baseName, int sequence) {
        return String.format("%s (%d)", baseName, sequence);
    }
}