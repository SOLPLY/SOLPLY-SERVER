package org.sopt.solply_server.domain.course.service;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailsDto;
import org.sopt.solply_server.domain.course.dto.response.CourseDetailGetResponse;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.repository.CourseBookmarkRepository;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceImageInfo;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.place.repository.PlaceBookmarkRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final CourseRepository courseRepository;
    private final CourseBookmarkRepository courseBookmarkRepository;
    private final PlaceBookmarkRepository placeBookmarkRepository;
    private final ImageUrlProvider imageUrlProvider;

    /**
     * 코스 상세 정보 조회
     */
    public CourseDetailGetResponse findCourseDetailsById(final Long userId, final Long courseId) {
        Course course = courseRepository.findByIdWithPlaces(courseId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.NOT_FOUND_ENTITY));

        // 코스 북마크 여부 확인 (TODO: Redis로 변경 필요)
        boolean isCourseBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);

        if (course.getCoursePlaces().isEmpty()) {
            return CourseDetailGetResponse.of(course, isCourseBookmarked, List.of());
        }

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(cp -> cp.getPlace().getId())
                .toList();

        // 장소 태그 정보를 영속성 컨텍스트에 로드
        courseRepository.findPlacesWithTagsByIds(placeIds);

        // 장소 북마크 상태를 한번에 조회 (TODO: Redis로 변경 필요)
        Map<Long, Boolean> placeBookmarkMap = getPlaceBookmarkMap(placeIds, userId);

        List<CoursePlaceDetailsDto> coursePlaces = course.getCoursePlaces().stream()
                .map(coursePlace -> convertToCoursePlaceDetailsDto(coursePlace, placeBookmarkMap))
                .toList();

        return CourseDetailGetResponse.of(course, isCourseBookmarked, coursePlaces);
    }

    private Map<Long, Boolean> getPlaceBookmarkMap(List<Long> placeIds, Long userId) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }

        // TODO: Redis 기반 북마크 조회로 변경 필요
        Set<Long> bookmarkedPlaceIds = placeBookmarkRepository.findBookmarkedPlaceIdsByUserIdAndPlaceIds(userId, placeIds);

        return placeIds.stream()
                .collect(Collectors.toMap(
                        placeId -> placeId,
                        bookmarkedPlaceIds::contains
                ));
    }

    /**
     * CoursePlace를 CoursePlaceDetailsDto로 변환
     */
    private CoursePlaceDetailsDto convertToCoursePlaceDetailsDto(CoursePlace coursePlace, Map<Long, Boolean> placeBookmarkMap) {
        Place place = coursePlace.getPlace();

        return CoursePlaceDetailsDto.of(
                place,
                getThumbnailUrl(place),
                place.getPrimaryTag(), // Place 엔티티 메서드 활용
                placeBookmarkMap.getOrDefault(place.getId(), false),
                coursePlace.getPlaceOrder()
        );
    }

    /**
     * 장소의 썸네일 이미지 URL 생성
     */
    private String getThumbnailUrl(Place place) {
        String fileKey = place.getThumbnailFileKey(); // Place 엔티티 메서드 활용
        return fileKey != null ? imageUrlProvider.getImageUrl(fileKey) : null;
    }
}