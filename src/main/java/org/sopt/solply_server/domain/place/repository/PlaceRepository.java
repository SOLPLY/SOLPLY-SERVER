package org.sopt.solply_server.domain.place.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.TagName;
import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    @Query("SELECT DISTINCT p FROM Place p " +
            "JOIN p.placeTags pt " +
            "JOIN pt.tag t " +
            "WHERE t.id = :mainTagId AND t.type = 'MAIN' " +
            "AND p.town = :town")
    List<Place> findPlacesByTownAndMainTag(
            @Param("town") Town town,
            @Param("mainTagId") Long mainTagId
    );


    @Query("""
        SELECT DISTINCT p FROM Place p
        JOIN p.placeTags pt1
        JOIN pt1.tag t1
        WHERE t1.id = :mainTagId AND t1.type = 'MAIN'
          AND p.town = :town
          AND (
            (:subTagOptionAIds IS NULL OR EXISTS (
                SELECT pt2 FROM PlaceTag pt2
                WHERE pt2.place = p
                  AND pt2.tag.id IN :subTagOptionAIds
                  AND pt2.tag.type = 'OPTION1'
            ))
            AND
            (:subTagOptionBIds IS NULL OR EXISTS (
                SELECT pt3 FROM PlaceTag pt3
                WHERE pt3.place = p
                  AND pt3.tag.id IN :subTagOptionBIds
                  AND pt3.tag.type = 'OPTION2'
            ))
          )
    """)
    List<Place> findPlacesByTownAndMainTagAndSubTags(
            @Param("town") Town town,
            @Param("mainTagId") Long mainTagId,
            @Param("subTagOptionAIds") List<Long> subTagOptionAIds,
            @Param("subTagOptionBIds") List<Long> subTagOptionBIds
    );


    @Query("SELECT p FROM Place p " +
            "JOIN FETCH p.town " +
            "WHERE p.id IN :placeIds")
    List<Place> findAllByIdsWithTown(@Param("placeIds") List<Long> placeIds);
}
