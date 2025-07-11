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

        List<Long> placeIds = course.getCoursePlaces().stream()
                .map(cp -> cp.getPlace().getId())
                .toList();

        if (!placeIds.isEmpty()) {
            // 영속성 컨텍스트에 태그 정보 로드
            courseRepository.findPlacesWithTagsByIds(placeIds);
        }

        boolean isCourseBookmarked = courseBookmarkRepository.existsByCourseIdAndUserId(courseId, userId);

        // 장소 북마크 상태를 한번에 조회
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

        Set<Long> bookmarkedPlaceIds = placeBookmarkRepository.findBookmarkedPlaceIdsByUserIdAndPlaceIds(userId, placeIds);

        return placeIds.stream()
                .collect(Collectors.toMap(
                        placeId -> placeId,
                        bookmarkedPlaceIds::contains
                ));
    }

    /**
     * CoursePlace를 CoursePlaceDetailDto로 변환
     */
    private CoursePlaceDetailsDto convertToCoursePlaceDetailsDto(CoursePlace coursePlace, Map<Long, Boolean> placeBookmarkMap) {
        Place place = coursePlace.getPlace();

        TagName primaryTag = getPrimaryTag(place);
        String thumbnailUrl = getThumbnailUrl(place);
        boolean isPlaceBookmarked = placeBookmarkMap.getOrDefault(place.getId(), false);

        return CoursePlaceDetailsDto.of(
                place,
                thumbnailUrl,
                primaryTag,
                isPlaceBookmarked,
                coursePlace.getPlaceOrder()
        );
    }

    /**
     * 장소의 1차 태그(MAIN) 추출
     */
    private TagName getPrimaryTag(Place place) {
        return place.getPlaceTags().stream()
                .map(PlaceTag::getTag)
                .filter(tag -> tag.getType() == TagType.MAIN)
                .findFirst()
                .map(Tag::getName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLACE_TAG_REQUIRED));
    }

    /**
     * 장소의 썸네일 이미지 URL 생성
     */
    private String getThumbnailUrl(Place place) {
        return place.getPlaceImageInfos().stream()
                .findFirst()
                .map(PlaceImageInfo::getImageFileKey)
                .map(imageUrlProvider::getImageUrl)
                .orElse(null);
    }
}