package org.sopt.solply_server.domain.recommend.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.dto.CoursePreviewDto;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.service.CourseBookmarkService;
import org.sopt.solply_server.domain.course.util.CourseUtils;
import org.sopt.solply_server.domain.recommend.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.recommend.dto.PlaceInfoDto;
import org.sopt.solply_server.domain.recommend.dto.response.PlaceRecommendationGetResponse;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.sopt.solply_server.domain.user.repository.UserRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendService {

    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final PersonaTagMappingStrategy personaTagMappingStrategy;
    private final ImageUrlProvider imageUrlProvider;
    private final TownValidator townValidator;
    private final CourseBookmarkService courseBookmarkService;
    private final CourseUtils courseUtils;
    private final EntityLoader entityLoader;


    /**
     * 사용자 페르소나에 맞는 장소 추천
     * - 사용자 페르소나 조회
     * - 페르소나에 맞는 추천 태그 조회
     * - 해당 타운의 장소들을 태그와 함께 조회
     * - 추천 점수(단순하게 매칭되는 태그 수) 계산 -> 바로 dto로 변환
     */
    public PlaceRecommendationGetResponse getRecommendPlaces(Long userId, Long townId) {
        // 사용자 페르소나 조회
        User user = entityLoader.getUser(userId);
        UserPersona persona = user.getPersona();
        if (persona == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_PERSONA);
        }

        // 페르소나에 맞는 추천 태그 조회
        List<TagName> recommendedTags = personaTagMappingStrategy.getTagsByPersona(persona);

        // 해당 타운의 장소들을 태그와 함께 조회
        List<Place> places = placeRepository.findPlacesByTownIdWithTags(townId);

        // 추천 점수(단순하게 매칭되는 태그 수) 계산 -> 바로 dto로 변환
        List<PlaceInfoDto> placeInfos = places.stream()
                .filter(place -> hasMatchingTags(place, recommendedTags)) // 매칭되는 태그가 있는 장소만
                .sorted((p1, p2) -> Integer.compare(
                        calculateMatchingTagsCount(p2, recommendedTags), // 내림차순
                        calculateMatchingTagsCount(p1, recommendedTags)
                ))
                .map(place -> PlaceInfoDto.from(
                        place.getId(),
                        place.getName(),
                        imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                        place.getPrimaryTag(),
                        place.getIntroduction()
                ))
                .collect(Collectors.toList());

        return new PlaceRecommendationGetResponse(placeInfos);
    }


    /**
     * 동네 ID에 해당하는 공유된 코스들을 추천
     */
    public CourseRecommendGetResponse getRecommendCourses(Long userId, Long townId) {
        townValidator.validateTownId(townId);

        List<Course> sharedCourses = courseRepository.findSharedCoursesByTownIdWithPlaces(townId);

        if (sharedCourses.isEmpty()) {
            log.info("동네 ID {}에 공유된 코스가 없습니다.", townId);
            return CourseRecommendGetResponse.from(List.of());
        }

        List<Long> courseIds = sharedCourses.stream()
                .map(Course::getId)
                .toList();

        // 장소 태그 정보를 미리 로드 (영속성 컨텍스트에 적재)
        courseRepository.findPlacesWithTagsByCourseIds(courseIds);

        // 북마크 정보 배치로 조회
        Map<Long, Boolean> courseBookmarkMap = courseBookmarkService.getBookmarkStatusMap(userId, courseIds);

        List<CoursePreviewDto> coursePreviewDtos = sharedCourses.stream()
                .map(course -> {
                    List<TagName> mainTags = courseUtils.extractTopTwoPlaceMainTags(course);
                    String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);
                    return CoursePreviewDto.of(course, mainTags, thumbnailUrl, courseBookmarkMap);
                })
                .toList();

        return CourseRecommendGetResponse.from(coursePreviewDtos);
    }

    private boolean hasMatchingTags(Place place, List<TagName> recommendedTags) {
        Set<TagName> placeTags = place.getPlaceTags().stream()
                .map(placeTag -> placeTag.getTag().getName())
                .collect(Collectors.toSet());

        return recommendedTags.stream().anyMatch(placeTags::contains);
    }

    private int calculateMatchingTagsCount(Place place, List<TagName> recommendedTags) {
        Set<TagName> placeTags = place.getPlaceTags().stream()
                .map(placeTag -> placeTag.getTag().getName())
                .collect(Collectors.toSet());

        return (int) recommendedTags.stream()
                .filter(placeTags::contains)
                .count();
    }
}