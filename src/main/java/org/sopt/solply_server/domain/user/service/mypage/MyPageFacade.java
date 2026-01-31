package org.sopt.solply_server.domain.user.service.mypage;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.user.dto.UserPlacePreviewDto;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyPageFacade {

    private final PlaceRepository placeRepository;
    private final ImageUrlProvider imageUrlProvider;
    private final MyPageBookmarkReader myPageBookmarkReader;

    public List<UserPlacePreviewDto> getMyPlacePreviewsTop3(User user) {
        List<Place> places = placeRepository.findTop3ByCreatedByAndActiveTrueOrderByCreatedAtDesc(user);
        List<Long> placeIds = places.stream().map(Place::getId).toList();

        Map<Long, Boolean> bookmarkMap =
                myPageBookmarkReader.getPlaceBookmarkMap(user.getId(), placeIds);

        return places.stream()
                .map(place -> UserPlacePreviewDto.of(
                        place.getId(),
                        place.getName(),
                        imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                        place.getActiveMainTag().map(Tag::getName).orElse(null),
                        bookmarkMap.getOrDefault(place.getId(), false)
                ))
                .toList();
    }

    public Page<PlacePreviewDto> getPlacesCreatedBy(Long userId, Pageable pageable) {
        Page<Place> places = placeRepository.findByUserIdWithTown(userId, pageable);
        List<Long> placeIds = places.stream().map(Place::getId).toList();

        Map<Long, Boolean> bookmarkMap =
                myPageBookmarkReader.getPlaceBookmarkMap(userId, placeIds);

        return places.map(place -> PlacePreviewDto.of(
                place.getId(),
                place.getName(),
                imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                place.getActiveMainTag().map(Tag::getName).orElse(null),
                bookmarkMap.getOrDefault(place.getId(), false),
                place.getTown().getId()
        ));
    }
}