package org.sopt.solply_server.domain.place.repository;

import java.util.List;
import org.sopt.solply_server.domain.place.entity.PlaceTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceTagRepository extends JpaRepository<PlaceTag, Long> {

    @Query("""
        SELECT pt FROM PlaceTag pt
        JOIN FETCH pt.tag t
        WHERE pt.place.id = :placeId
          AND t.active = true
    """)
    List<PlaceTag> findAllByPlaceId(@Param("placeId") Long placeId);
}
