package org.sopt.solply_server.domain.course.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class CourseNameGenerator {

    /**
     * 중복되지 않는 고유한 코스명 생성
     */
    public String generateUniqueName(String baseName, List<String> existingNames) {
        log.debug("코스명 생성 시작 - baseName: '{}', 기존 코스명 개수: {}", baseName, existingNames.size());

        if (existingNames.isEmpty()) {
            log.debug("기존 코스명이 없어 원본 이름 사용: '{}'", baseName);
            return baseName;
        }

        // 중복 번호 찾기, 다음 번호 생성
        int nextSequence = findNextSequenceNumber(baseName, existingNames);
        String uniqueName = generateNameWithSequence(baseName, nextSequence);

        log.debug("고유 코스명 생성 완료: '{}' -> '{}'", baseName, uniqueName);
        return uniqueName;
    }

    /**
     * 기본 이름과 순서 번호로 코스명 생성
     */
    public String generateNameWithSequence(String baseName, int sequence) {
        if (sequence == 0) {
            return baseName;
        }
        return String.format("%s (%d)", baseName, sequence);
    }

    /**
     * 다음 사용 가능한 순서 번호 찾기
     */
    private int findNextSequenceNumber(String baseName, List<String> existingNames) {
        // 정규식: "기본이름 (숫자)" 패턴
        // Pattern.quote()로 특수문자 이스케이프 처리
        Pattern pattern = Pattern.compile(Pattern.quote(baseName) + "\\s*\\((\\d+)\\)");
        int maxNumber = -1;

        boolean baseNameExists = existingNames.contains(baseName);
        if (baseNameExists) {
            maxNumber = 0;
        }

        for (String name : existingNames) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                try {
                    int number = Integer.parseInt(matcher.group(1));
                    maxNumber = Math.max(maxNumber, number);
                } catch (NumberFormatException e) {
                    log.warn("코스명에서 숫자 파싱 실패: '{}'", name);
                }
            }
        }

        int nextSequence = maxNumber + 1;

        log.debug("코스명 생성 분석 - baseName: '{}', 최대 번호: {}, 다음 번호: {}",
                baseName, maxNumber, nextSequence);

        return nextSequence;
    }
}