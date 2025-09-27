package org.sopt.solply_server.domain.user.service.mypage;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.sopt.solply_server.domain.user.dto.UserPlacePreviewDto;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MyPageFacade {

    private final PlaceRepository placeRepository;

    @Transactional(readOnly = true)
    public List<Place> getMyPlacesTop3(User user) {
        return placeRepository.findTop3ByCreatedByOrderByCreatedAtDesc(user);
    }

}