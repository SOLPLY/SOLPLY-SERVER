package org.sopt.solply_server.domain.place.repository;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceSearchConditionDto;
import org.sopt.solply_server.domain.place.entity.Place;

public interface PlaceRepositoryCustom {
    List<Place> findPlacesByConditions(PlaceSearchConditionDto placeSearchConditionDto);
}