package org.sopt.solply_server.domain.course.service;

import java.util.List;
import java.util.Objects;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.springframework.stereotype.Component;

@Component
public class CourseRetrievalTextBuilder {

    /**
     * 코스 임베딩용 retrieval_text 생성.
     * 호출자는 course.coursePlaces → place → placeTags 가 이미 로딩된 상태여야 한다.
     */
    public String build(Course course) {
        String courseName = course.getName();
        String townName = course.getTown().getName();
        String category = course.getTag() != null ? course.getTag().getName() : "기타";
        String introduction = course.getIntroduction();
        int placeCount = course.getCoursePlaces().size();
        String lengthDesc = placeCount <= 3 ? "짧은" : "반나절";

        List<String> mainTagNames = course.getCoursePlaces().stream()
                .map(CoursePlace::getPlace)
                .map(place -> place.getMainTag().filter(Tag::isActive).map(Tag::getName).orElse(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        String tagList = mainTagNames.isEmpty() ? "다양한 장소" : String.join(", ", mainTagNames);

        StringBuilder sb = new StringBuilder();
        sb.append(courseName).append("은 ").append(townName).append("에 위치한 ").append(category).append(" 코스다.\n");
        if (introduction != null && !introduction.isBlank()) {
            sb.append(introduction).append("\n");
        }
        sb.append("총 ").append(placeCount).append("개 장소로 구성된 ").append(lengthDesc).append(" 코스다.\n");
        sb.append("주요 장소 유형: ").append(tagList);

        return sb.toString();
    }
}
