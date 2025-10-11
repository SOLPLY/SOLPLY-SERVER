package org.sopt.solply_server.domain.place.repository.querydsl;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceSearchConditionDto;
import org.sopt.solply_server.domain.place.entity.Place;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PlaceRepositoryCustom {
    List<Place> findPlacesByConditions(PlaceSearchConditionDto placeSearchConditionDto);
    List<Place> findPlacesWithTownByKeyword(String keyword);
    Page<Place> findByUserIdWithTown(Long userId, Pageable pageable);
}