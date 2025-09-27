package org.sopt.solply_server.domain.user.service.mypage;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.util.s3.ImageUrlProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MyPageFacade {

    private final PlaceRepository placeRepository;
    private final ImageUrlProvider imageUrlProvider;

    @Transactional(readOnly = true)
    public List<Place> getMyPlacesTop3(User user) {
        return placeRepository.findTop3ByCreatedByOrderByCreatedAtDesc(user);
    }

    @Transactional(readOnly = true)
    public Page<PlacePreviewDto> getPlacesCreatedBy(Long userId, Pageable pageable) {
        Page<Place> places = placeRepository.findByCreatedByIdOrderByCreatedAtDesc(userId, pageable);
        return places.map(place -> PlacePreviewDto.of(
                    place.getId(),
                    place.getName(),
                    imageUrlProvider.getImageUrl(place.getThumbnailFileKey()),
                    place.getMainTag(),
                    false // isBookmarked 정보는 여기에 포함되지 않음
                )
        );
    }

}