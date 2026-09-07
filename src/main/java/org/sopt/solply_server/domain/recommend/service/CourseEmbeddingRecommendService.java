package org.sopt.solply_server.domain.recommend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.entity.CoursePlace;
import org.sopt.solply_server.domain.course.entity.CourseSearchDocument;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.repository.CourseSearchDocumentRepository;
import org.sopt.solply_server.domain.course.util.CourseUtils;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.recommend.dto.RecommendedCourseDto;
import org.sopt.solply_server.domain.recommend.dto.response.EmbeddingCourseRecommendGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.ai.CosineSimilarityUtil;
import org.sopt.solply_server.global.ai.EmbeddingService;
import org.sopt.solply_server.global.ai.ReasonGenerationService;
import org.sopt.solply_server.global.ai.ReasonGenerationService.CourseContext;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseEmbeddingRecommendService {

    private static final int TOP_K = 3;

    private final CourseSearchDocumentRepository courseSearchDocumentRepository;
    private final CourseRepository courseRepository;
    private final EmbeddingService embeddingService;
    private final ReasonGenerationService reasonGenerationService;
    private final CourseUtils courseUtils;
    private final EntityLoader entityLoader;

    public EmbeddingCourseRecommendGetResponse recommendByQuery(String query, Long townId, Long userId) {
        User user = entityLoader.getUser(userId);
        String userName = user.getNickname();

        float[] queryVector = embeddingService.embed(query);

        // parentTown이 null이면 광역 동네 → 자식 동네들의 공유 코스 포함 조회
        Town town = entityLoader.getActiveTown(townId);
        List<CourseSearchDocument> candidates = town.getParent() == null
                ? courseSearchDocumentRepository.findActiveSharedByParentTownIdWithEmbedding(townId)
                : courseSearchDocumentRepository.findActiveSharedByTownIdWithEmbedding(townId);

        // 코사인 유사도 계산 후 상위 TOP_K 선정
        List<Long> topCourseIds = candidates.stream()
                .filter(doc -> {
                    if (doc.getEmbedding() == null) {
                        log.warn("임베딩 데이터가 없어 추천 후보에서 제외합니다. courseId={}", doc.getCourseId());
                        return false;
                    }
                    return true;
                })
                .flatMap(doc -> {
                    OptionalDouble score = CosineSimilarityUtil.calculate(queryVector, doc.getEmbedding());
                    if (score.isEmpty()) {
                        log.warn("임베딩 차원 불일치로 추천 후보에서 제외합니다. courseId={}, queryDim={}, docDim={}",
                                doc.getCourseId(), queryVector.length, doc.getEmbedding().length);
                        return java.util.stream.Stream.empty();
                    }
                    return java.util.stream.Stream.of(new ScoredDoc(doc.getCourseId(), score.getAsDouble()));
                })
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(TOP_K)
                .map(ScoredDoc::courseId)
                .toList();

        if (topCourseIds.isEmpty()) {
            return new EmbeddingCourseRecommendGetResponse(List.of());
        }

        // Top-K 코스의 전체 데이터 로딩 (tag, town, coursePlaces, place, placeTags 포함)
        List<Course> topCourses = courseRepository.findByIdInWithAllForRecommendation(topCourseIds);

        // 유사도 점수 순서 복원 (DB 조회 결과 순서와 topCourseIds 순서가 다를 수 있음)
        List<Course> orderedCourses = topCourseIds.stream()
                .map(id -> topCourses.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null))
                .filter(Objects::nonNull)
                .toList();

        List<CourseContext> courseContexts = orderedCourses.stream()
                .map(course -> new CourseContext(
                        course.getName(),
                        course.getTown().getName(),
                        buildRetrievalTextSummary(course)
                ))
                .toList();

        List<String> reasons = reasonGenerationService.generateCourseReasons(query, userName, courseContexts);

        List<RecommendedCourseDto> result = new ArrayList<>();
        for (int i = 0; i < orderedCourses.size(); i++) {
            Course course = orderedCourses.get(i);
            String reason = (i < reasons.size()) ? reasons.get(i) : "";
            result.add(toDto(course, reason));
        }

        return new EmbeddingCourseRecommendGetResponse(result);
    }

    private RecommendedCourseDto toDto(Course course, String reason) {
        String courseTag = TagViewUtils.getActiveNameOrNull(course.getTag());
        Long townId = course.getTown().getId();
        String townName = course.getTown().getName();
        String thumbnailImageUrl = courseUtils.getCourseThumbnailUrl(course);

        List<String> placeMainTags = course.getCoursePlaces().stream()
                .map(CoursePlace::getPlace)
                .map(place -> place.getMainTag().filter(Tag::isActive).map(Tag::getName).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        return new RecommendedCourseDto(
                course.getId(),
                course.getName(),
                thumbnailImageUrl,
                courseTag,
                townId,
                townName,
                reason,
                placeMainTags
        );
    }

    /**
     * LLM 프롬프트용 코스 요약 텍스트.
     * CourseSearchDocument의 retrievalText 대신 현재 로딩된 course 데이터로 즉석 생성한다.
     */
    private String buildRetrievalTextSummary(Course course) {
        String tagName = TagViewUtils.getActiveNameOrNull(course.getTag());
        int placeCount = course.getCoursePlaces().size();
        List<String> placeMainTagNames = course.getCoursePlaces().stream()
                .map(CoursePlace::getPlace)
                .map(p -> p.getMainTag().filter(Tag::isActive).map(Tag::getName).orElse(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        return String.format("%s 코스, 장소 %d개 (%s)",
                tagName != null ? tagName : "기타",
                placeCount,
                placeMainTagNames.isEmpty() ? "다양한 장소" : String.join(", ", placeMainTagNames));
    }

    private record ScoredDoc(Long courseId, double score) {}
}
