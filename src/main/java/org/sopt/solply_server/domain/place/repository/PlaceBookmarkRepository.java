package org.sopt.solply_server.domain.place.repository;

import org.sopt.solply_server.domain.place.entity.PlaceBookmark;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceBookmarkRepository extends JpaRepository<PlaceBookmark, Long> {

    boolean existsByPlaceIdAndUserId(Long placeId, Long placeId1);
}
