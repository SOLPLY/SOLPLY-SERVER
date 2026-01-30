package org.sopt.solply_server.domain.admin.place.service.facade;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.PlaceRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminPlaceQueryFacade {
    private final PlaceRepository placeRepository;

    public List<Place> findPlacesWithTownByKeyword(String keyword) {
        return placeRepository.findPlacesWithTownByKeyword(keyword);
    }
}