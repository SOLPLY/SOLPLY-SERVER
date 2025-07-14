package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.request.CourseCreateRequest;
import org.sopt.solply_server.domain.course.dto.response.CourseCreateResponse;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CourseCreationService {

    private final CourseRepository courseRepository;
    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final CourseBookmarkService courseBookmarkService;

    /**
     * 새로운 코스 생성
     */
    public CourseCreateResponse createCourse(Long userId, CourseCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_USER));

        validateCourseRequest(request);
        List<Place> places = validateAndGetPlaces(request);

        // 모든 장소가 같은 동네에 속하는지 검증
        Town town = places.get(0).getTown();
        validateSameTown(places, town);

        String courseName = generateUniqueCourseName(request.originalCourseId(), town);
        Course course = createNewCourse(courseName, town, places, request);

        Course savedCourse = courseRepository.save(course);

        // 새로 생성된 코스는 자동으로 북마크에 등록
        try {
            courseBookmarkService.createCourseBookmark(userId, savedCourse.getId());
            log.info("새 코스 생성 및 북마크 등록 완료 - userId: {}, originalCourseId: {}, newCourseId: {}, courseName: '{}'",
                    userId, request.originalCourseId(), savedCourse.getId(), courseName);
        } catch (Exception e) {
            log.error("코스 북마크 등록 실패 - userId: {}, courseId: {}", userId, savedCourse.getId(), e);
        }

        return CourseCreateResponse.from(savedCourse.getId());
    }

    private void validateCourseRequest(CourseCreateRequest request) {
        List<CourseCreateRequest.CoursePlaceRequest> places = request.places();

        // originalCourseId 검증
        if (request.originalCourseId() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "원본 코스 ID는 필수입니다.");
        }

        // 순서 검증 (1부터 연속)
        List<Integer> sequences = places.stream()
                .map(CourseCreateRequest.CoursePlaceRequest::sequence)
                .sorted()
                .toList();

        for (int i = 0; i < sequences.size(); i++) {
            if (sequences.get(i) != i + 1) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                        "순서는 1부터 시작하여 연속되어야 합니다.");
            }
        }

        // 중복 장소 검증
        Set<Long> uniquePlaceIds = new HashSet<>();
        for (CourseCreateRequest.CoursePlaceRequest placeRequest : places) {
            if (!uniquePlaceIds.add(placeRequest.placeId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                        "중복된 장소가 포함되어 있습니다.");
            }
        }
    }

    private List<Place> validateAndGetPlaces(CourseCreateRequest request) {
        List<Long> placeIds = request.places().stream()
                .map(CourseCreateRequest.CoursePlaceRequest::placeId)
                .toList();

        List<Place> places = placeRepository.findAllById(placeIds);

        // 존재하지 않는 장소 검증
        if (places.size() != placeIds.size()) {
            Set<Long> foundIds = places.stream()
                    .map(Place::getId)
                    .collect(Collectors.toSet());

            List<Long> missingIds = placeIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .toList();

            log.warn("존재하지 않는 장소 ID들: {}", missingIds);
            throw new EntityNotFoundException(ErrorCode.NOT_FOUND_PLACE);
        }

        return places;
    }

    private void validateSameTown(List<Place> places, Town referenceTown) {
        boolean allInSameTown = places.stream()
                .allMatch(place -> place.getTown().getId().equals(referenceTown.getId()));

        if (!allInSameTown) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_BODY,
                    "모든 장소는 같은 동네에 속해야 합니다.");
        }
    }

    /**
     * 중복되지 않는 코스명 생성
     */
    private String generateUniqueCourseName(Long originalCourseId, Town town) {
        Course originalCourse = courseRepository.findById(originalCourseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));

        String baseName = originalCourse.getName();

        // 기존 코스명들 조회 (패턴 매칭)
        String namePattern = baseName + "%";
        List<String> existingNames = courseRepository
                .findCourseNamesByTownAndNamePattern(town.getId(), namePattern);

        log.debug("기존 코스명들: baseName='{}', existingNames={}", baseName, existingNames);

        if (existingNames.isEmpty()) {
            return baseName;
        }

        // 중복 번호 찾기, 다음 번호 생성
        int nextSequence = findNextSequenceNumber(baseName, existingNames);

        return Course.generateUniqueName(baseName, nextSequence);
    }

    /**
     * 다음 순서 번호 찾기
     */
    private int findNextSequenceNumber(String baseName, List<String> existingNames) {
        // 정규식: "기본이름 (숫자)" 패턴
        Pattern pattern = Pattern.compile(Pattern.quote(baseName) + "\\s*\\((\\d+)\\)");
        Set<Integer> usedNumbers = new HashSet<>();

        boolean baseNameExists = existingNames.contains(baseName);
        if (baseNameExists) {
            usedNumbers.add(0); // 기본 이름을 0번으로 간주
        }

        // 기존 이름들에서 숫자 추출
        for (String name : existingNames) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                try {
                    int number = Integer.parseInt(matcher.group(1));
                    usedNumbers.add(number);
                } catch (NumberFormatException e) {
                    log.warn("코스명에서 숫자 파싱 실패: {}", name);
                }
            }
        }

        int sequence = baseNameExists ? 1 : 0;
        while (usedNumbers.contains(sequence)) {
            sequence++;
        }

        log.debug("코스명 생성 - baseName: '{}', usedNumbers: {}, nextSequence: {}",
                baseName, usedNumbers, sequence);

        return sequence;
    }

    /**
     * 새 코스 생성
     */
    private Course createNewCourse(String courseName, Town town, List<Place> places, CourseCreateRequest request) {
        // 원본 코스의 소개글 가져오기
        String introduction = getOriginalCourseIntroduction(request.originalCourseId());
        Course course = Course.createUserCourse(courseName, introduction, town);

        // 장소들 순서대로 매핑
        Map<Long, Place> placeMap = places.stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        // CoursePlace 생성 및 추가
        List<CoursePlace> coursePlaces = request.places().stream()
                .sorted(Comparator.comparing(CourseCreateRequest.CoursePlaceRequest::sequence))
                .map(placeRequest -> {
                    Place place = placeMap.get(placeRequest.placeId());
                    return CoursePlace.create(course, place, placeRequest.sequence());
                })
                .toList();

        coursePlaces.forEach(course::addCoursePlace);

        return course;
    }

    private String getOriginalCourseIntroduction(Long originalCourseId) {
        Course originalCourse = courseRepository.findById(originalCourseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_COURSE));

        return originalCourse.getIntroduction();
    }
}