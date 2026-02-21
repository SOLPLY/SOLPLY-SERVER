package org.sopt.solply_server.domain.recommend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkRepository;
import org.sopt.solply_server.domain.course.dto.CoursePreviewDto;
import org.sopt.solply_server.domain.course.entity.Course;
import org.sopt.solply_server.domain.course.repository.CourseRepository;
import org.sopt.solply_server.domain.course.service.facade.CourseBookmarkFacade;
import org.sopt.solply_server.domain.course.util.CourseUtils;
import org.sopt.solply_server.domain.recommend.cache.DailyRecommendCache;
import org.sopt.solply_server.domain.recommend.dto.response.CourseRecommendGetResponse;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.recommend.dto.PlaceInfoDto;
import org.sopt.solply_server.domain.recommend.dto.response.PlaceRecommendationGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.repository.TagPersonaMappingRepository;
import org.sopt.solply_server.domain.town.util.TownValidator;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.util.EntityLoader;
import org.sopt.solply_server.global.util.TagViewUtils;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendService {

    private final PlaceRepository placeRepository;
    private final TagPersonaMappingRepository tagPersonaMappingRepository;
    private final CourseRepository courseRepository;
    private final ImageUrlProvider imageUrlProvider;
    private final TownValidator townValidator;
    private final CourseBookmarkFacade courseBookmarkFacade;
    private final CourseUtils courseUtils;
    private final EntityLoader entityLoader;
    private final BookmarkRepository bookmarkRepository;
    private final DailyRecommendCache dailyRecommendCache;

    // 점수 가중치 (운영하면서 조절)
    private static final int PERSONA_W = 10;
    private static final int BOOKMARK_W = 1;

    private static final int TOP_N_CANDIDATES = 10;
    private static final int PICK_K = 3;
    private static final int COOLDOWN_DAYS = 3;

    /**
     * 사용자 페르소나 + 최근 1달 북마크(태그 프로필) 기반 장소 추천
     * 요구사항:
     * 1) 하루동안 동일한 추천(캐시)
     * 2) 최근 3일 추천 쿨다운(재노출 방지)
     * 3) Top10 후보에서 랜덤(가중치)으로 3개 선택
     */
    public PlaceRecommendationGetResponse getRecommendPlaces(Long userId, Long townId) {
        LocalDate today = LocalDate.now();

        // 0) 오늘 추천 고정: 캐시 hit면 그대로 반환
        List<Long> cachedPlaceIds = dailyRecommendCache.getTodayPlaceIds(userId, townId, today);
        if (cachedPlaceIds != null && !cachedPlaceIds.isEmpty()) {
            // (주의) findByIdIn은 순서 보장 X
            List<Place> cachedPlaces = placeRepository.findByIdInWithTags(cachedPlaceIds);
            Map<Long, Place> byId = cachedPlaces.stream().collect(Collectors.toMap(Place::getId, Function.identity()));
            List<PlaceInfoDto> dtos = cachedPlaceIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .map(this::toDto)
                    .toList();

            return new PlaceRecommendationGetResponse(dtos);
        }

        // 1) 유저 페르소나
        User user = entityLoader.getUser(userId);
        UserPersona persona = user.getPersona();
        if (persona == null) throw new BusinessException(ErrorCode.NOT_FOUND_PERSONA);

        // 2) persona 추천 태그 Set
        Set<Long> personaTagIds =
                tagPersonaMappingRepository.findActiveByPersonaOrderByWeightDesc(persona).stream()
                        .map(m -> m.getTag().getId())
                        .collect(Collectors.toSet());

        // 3) 최근 1달 북마크한 PLACE ids (DB 기준 createdAt)
        LocalDateTime since = LocalDateTime.now().minusMonths(1);
        List<Long> recentBookmarkedPlaceIds =
                bookmarkRepository.findTargetIdsByUserAndTypeSince(userId, BookmarkTargetType.PLACE, since);

        // 4) 북마크 태그 프로필(tagId -> count)
        final Map<Long, Integer> bookmarkTagCount =
                (recentBookmarkedPlaceIds == null || recentBookmarkedPlaceIds.isEmpty())
                        ? Map.of()
                        : placeRepository.findByIdInWithTags(recentBookmarkedPlaceIds).stream()
                                .flatMap(p -> p.getTags().stream())
                                .filter(Tag::isActive)
                                .map(Tag::getId)
                                .collect(Collectors.toMap(
                                        tagId -> tagId,
                                        tagId -> 1,
                                        Integer::sum
                                ));

        // 5) 동네 장소 조회(+tags)
        List<Place> townPlaces = placeRepository.findPlacesByTownIdWithTags(townId);
        if (townPlaces == null || townPlaces.isEmpty()) {
            return new PlaceRecommendationGetResponse(List.of());
        }

        // 6) 점수 계산 → 후보 Top10 추림 (쿨다운 적용 + 필요시 완화)
        List<ScoredPlace> topCandidates = buildTopCandidates(
                townPlaces,
                personaTagIds,
                bookmarkTagCount,
                userId, townId, today
        );

        if (topCandidates.isEmpty()) {
            return new PlaceRecommendationGetResponse(List.of());
        }

        // 7) Top10에서 가중치 랜덤으로 3개 뽑기(중복 없이)
        List<ScoredPlace> picked = pickWeightedRandomWithoutDup(topCandidates, PICK_K);

        // 8) 오늘 결과 저장(하루 고정)
        List<Long> pickedIds = picked.stream()
                .map(sp -> sp.place().getId())
                .toList();
        dailyRecommendCache.saveTodayRecommendedPlaceIds(userId, townId, today, pickedIds, COOLDOWN_DAYS);

        // 9) 응답
        List<PlaceInfoDto> placeInfos = picked.stream()
                .map(sp -> toDto(sp.place()))
                .toList();

        return new PlaceRecommendationGetResponse(placeInfos);
    }

    private ScoredPlace scorePlace(Place place, Set<Long> personaTagIds, Map<Long, Integer> bookmarkTagCount) {
        // placeTagIds 1회 생성
        Set<Long> placeTagIds = place.getActiveTagIds();

        int personaScore = 0;
        int bookmarkScore = 0;

        for (Long tagId : placeTagIds) {
            if (personaTagIds.contains(tagId)) personaScore++;
            bookmarkScore += bookmarkTagCount.getOrDefault(tagId, 0);
        }

        int finalScore = personaScore * PERSONA_W + bookmarkScore * BOOKMARK_W;
        return new ScoredPlace(place, finalScore, personaScore, bookmarkScore);
    }

    /**
     * 동네 ID에 해당하는 공유된 코스들을 추천
     */
    public CourseRecommendGetResponse getRecommendCourses(Long userId, Long townId) {
        townValidator.validateTownId(townId);

        List<Course> sharedCourses = courseRepository.findActiveSharedCoursesByTownIdWithPlaces(townId);

        if (sharedCourses.isEmpty()) {
            log.info("동네 ID {}에 공유된 코스가 없습니다.", townId);
            return CourseRecommendGetResponse.from(List.of());
        }

        List<Long> courseIds = sharedCourses.stream()
                .map(Course::getId)
                .toList();

        // 북마크 정보 배치로 조회
        Map<Long, Boolean> courseBookmarkMap = courseBookmarkFacade.getBookmarkStatusMap(userId, courseIds);

        List<CoursePreviewDto> coursePreviewDtos = sharedCourses.stream()
                .map(course -> {
                    Tag courseTag = course.getTag();
                    String courseTagName = null;
                    if (courseTag != null) {
                        courseTagName = courseTag.isActive() ? courseTag.getName() : null;
                    }

                    String thumbnailUrl = courseUtils.getCourseThumbnailUrl(course);
                    return CoursePreviewDto.of(
                            course,
                            courseTagName,
                            thumbnailUrl,
                            courseBookmarkMap
                    );
                })
                .toList();

        return CourseRecommendGetResponse.from(coursePreviewDtos);
    }

    /**
     * 점수 비례(가중치)로 중복 없이 k개 선택.
     * - 후보가 적으면 가능한 만큼만 반환.
     * - Top10 수준이면 성능 문제 없음.
     */
    private List<ScoredPlace> pickWeightedRandomWithoutDup(List<ScoredPlace> candidates, int k) {
        if (candidates == null || candidates.isEmpty() || k <= 0) return List.of();

        List<ScoredPlace> pool = new ArrayList<>(candidates);
        List<ScoredPlace> picked = new ArrayList<>(Math.min(k, pool.size()));

        for (int i = 0; i < k && !pool.isEmpty(); i++) {
            // 가중치: finalScore 기반 (0 방지)
            int minScore = pool.stream().mapToInt(ScoredPlace::finalScore).min().orElse(0);
            long totalWeight = 0L;

            long[] prefix = new long[pool.size()];
            for (int idx = 0; idx < pool.size(); idx++) {
                int w = Math.max(1, pool.get(idx).finalScore() - minScore + 1);
                totalWeight += w;
                prefix[idx] = totalWeight;
            }

            long r = ThreadLocalRandom.current().nextLong(totalWeight) + 1;
            int chosenIdx = lowerBound(prefix, r);

            picked.add(pool.remove(chosenIdx));
        }

        return picked;
    }

    private int lowerBound(long[] prefix, long target) {
        int lo = 0, hi = prefix.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (prefix[mid] >= target) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private PlaceInfoDto toDto(Place place) {
        return PlaceInfoDto.from(
                place.getId(),
                place.getName(),
                imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                TagViewUtils.getActiveNameOrNull(place.getMainTag().orElse(null)),
                place.getIntroduction()
        );
    }

    private record ScoredPlace(Place place, int finalScore, int personaScore, int bookmarkScore) {}

    private List<ScoredPlace> buildTopCandidates(
            List<Place> townPlaces,
            Set<Long> personaTagIds,
            Map<Long, Integer> bookmarkTagCount,
            Long userId,
            Long townId,
            LocalDate today
    ) {
        // 1차: 기본 쿨다운(3일)
        List<ScoredPlace> candidates = scoreAndPickTopN(
                townPlaces, personaTagIds, bookmarkTagCount,
                getCooldownIds(userId, townId, today, COOLDOWN_DAYS)
        );

        // 후보가 너무 적으면 → 쿨다운 완화(1일)
        if (candidates.size() < PICK_K) {
            candidates = scoreAndPickTopN(
                    townPlaces, personaTagIds, bookmarkTagCount,
                    getCooldownIds(userId, townId, today, 1)
            );
        }

        // 그래도 적으면 → 쿨다운 해제(0일 = 제외 없음)
        if (candidates.size() < PICK_K) {
            candidates = scoreAndPickTopN(
                    townPlaces, personaTagIds, bookmarkTagCount,
                    Set.of()
            );
        }

        return candidates;
    }

    private Set<Long> getCooldownIds(Long userId, Long townId, LocalDate today, int days) {
        if (days <= 0) return Set.of();
        return Optional.ofNullable(dailyRecommendCache.getCooldownPlaceIds(userId, townId, today, days))
                .orElse(Set.of());
    }

    private List<ScoredPlace> scoreAndPickTopN(
            List<Place> townPlaces,
            Set<Long> personaTagIds,
            Map<Long, Integer> bookmarkTagCount,
            Set<Long> cooldownIds
    ) {
        return townPlaces.stream()
                .filter(p -> !cooldownIds.contains(p.getId()))
                .map(p -> scorePlace(p, personaTagIds, bookmarkTagCount))
                .filter(sp -> sp.finalScore() > 0)
                .sorted((a, b) -> Integer.compare(b.finalScore(), a.finalScore()))
                .limit(TOP_N_CANDIDATES)
                .toList();
    }

}