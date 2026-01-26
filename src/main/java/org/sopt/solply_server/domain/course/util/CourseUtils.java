package org.sopt.solply_server.domain.course.util;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CourseUtils {

    private final ImageUrlProvider imageUrlProvider;

    /**
     * 코스 썸네일 URL 조회
     * 첫 번째 장소의 이미지를 썸네일로 사용
     */
    public String getCourseThumbnailUrl(Course course) {
        return course.getCoursePlaces().stream()
                .findFirst()
                .map(CoursePlace::getPlace)
                .map(Place::getThumbnailFileKey)
                .map(imageUrlProvider::getImageUrl)
                .orElse(null);
    }

    /**
     * 코스가 비어있는지 확인
     */
    public boolean isEmpty(Course course) {
        return course.getCoursePlaces().isEmpty();
    }

    /**
     * 코스의 주요 태그 2개 조회
     * - 장소의 순서에 따라 정렬 후, 첫 번째와 두 번째 장소의 주요 태그를 추출
     */
    public List<String> extractTopTwoPlaceMainTags(final Course course) {
        return course.getCoursePlaces().stream()
                .sorted(Comparator.comparing(CoursePlace::getPlaceOrder))
                .limit(2)
                .map(CoursePlace::getPlace)
                .map(place -> place.getActiveMainTag()
                        .map(Tag::getName)
                        .orElse(null)
                )
                .filter(Objects::nonNull)
                .toList();
    }
}