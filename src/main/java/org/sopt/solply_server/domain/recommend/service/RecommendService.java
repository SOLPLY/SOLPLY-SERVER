package org.sopt.solply_server.domain.recommend.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.recommend.dto.PlaceInfoDto;
import org.sopt.solply_server.domain.recommend.dto.response.PlaceRecommendationGetResponse;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.tag.entity.TagType;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.domain.user.entity.UserPersona;
import org.sopt.solply_server.domain.user.repository.UserRepository;
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
    private final PersonaTagMappingStrategy personaTagMappingStrategy;
    private final ImageUrlProvider imageUrlProvider;

    public PlaceRecommendationGetResponse getRecommendPlaces(Long userId, Long townId) {
        // 사용자 페르소나 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        UserPersona persona = user.getPersona();
        if (persona == null) {
            throw new IllegalArgumentException("사용자의 페르소나가 설정되지 않았습니다.");
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