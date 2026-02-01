package org.sopt.solply_server.domain.admin.place.repository.querydsl;

import java.util.List;
import org.sopt.solply_server.domain.place.entity.Place;

public interface AdminPlaceRepositoryCustom {
    List<Place> findPlacesWithTownByKeyword(String keyword);
}