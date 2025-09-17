package org.sopt.solply_server.domain.place.repository.querydsl;

import java.util.List;
import org.sopt.solply_server.domain.place.dto.PlaceSearchConditionDto;
import org.sopt.solply_server.domain.place.entity.Place;

public interface PlaceRepositoryCustom {
    List<Place> findPlacesByConditions(PlaceSearchConditionDto placeSearchConditionDto);
    List<Place> findPlacesByKeyword(String keyword);
}