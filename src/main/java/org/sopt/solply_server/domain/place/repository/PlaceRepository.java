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

    @Query("SELECT DISTINCT p FROM Place p " +
            "JOIN p.placeTags pt1 " +
            "JOIN pt1.tag t1 " +
            "JOIN p.placeTags pt2 " +
            "JOIN pt2.tag t2 " +
            "WHERE t1.id = :mainTagId AND t1.type = 'MAIN' " +
            "AND t2.id IN :subTagIds AND t2.type IN ('OPTION1', 'OPTION2') " +
            "AND p.town = :town")
    List<Place> findPlacesByTownAndMainTagAndSubTags(
            @Param("town") Town town,
            @Param("mainTagId") Long mainTagId,
            @Param("subTagIds") List<Long> subTagIds
    );
}
