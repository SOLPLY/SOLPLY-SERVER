package org.sopt.solply_server.domain.course.mapper;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.dto.CourseBookmarkDto;
import org.sopt.solply_server.domain.course.dto.CourseFolderDto;
import org.sopt.solply_server.domain.course.dto.CoursePlaceDetailsDto;
import org.sopt.solply_server.domain.course.dto.CoursePreviewDto;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CourseMapper {

    private final ImageUrlProvider imageUrlProvider;

    public CourseFolderDto toCourseFolderDto(Course course, List<TagName> primaryTags, String thumbnailUrl) {
        return CourseFolderDto.builder()
                .townId(course.getTown().getId())
                .townName(course.getTown().getName())
                .courseName(course.getName())
                .primaryTags(primaryTags)
                .thumbnailUrl(thumbnailUrl)
                .build();
    }

    public CoursePreviewDto toCourseRecommendDto(Course course, List<TagName> mainTags, String thumbnailUrl, Map<Long, Boolean> courseBookmarkMap) {
        return CoursePreviewDto.of(
                course,
                thumbnailUrl,
                mainTags,
                courseBookmarkMap.getOrDefault(course.getId(), false)
        );
    }

    public CoursePlaceDetailsDto toCoursePlaceDetailsDto(CoursePlace coursePlace, Map<Long, Boolean> placeBookmarkMap) {
        Place place = coursePlace.getPlace();
        String thumbnailUrl = place.getThumbnailFileKey() != null ? imageUrlProvider.getImageUrl(place.getThumbnailFileKey()) : null;

        return CoursePlaceDetailsDto.of(
                place,
                thumbnailUrl,
                place.getPrimaryTag(),
                placeBookmarkMap.getOrDefault(place.getId(), false),
                coursePlace.getPlaceOrder()
        );
    }

    public CourseBookmarkDto toCourseBookmarkDto(Course course, String thumbnailUrl,
                                                 List<TagName> mainTags, boolean isActive) {
        return CourseBookmarkDto.of(course, thumbnailUrl, mainTags, isActive);
    }
}