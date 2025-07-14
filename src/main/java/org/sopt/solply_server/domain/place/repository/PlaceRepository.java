package org.sopt.solply_server.domain.place.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.town.entity.Town;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 통합된 태그 기반 장소 조회
     * - mainTagId가 null이면 모든 장소 조회
     * - subTag 리스트가 null/empty면 해당 조건 무시
     */
    @Query("""
        SELECT DISTINCT p FROM Place p
        LEFT JOIN p.placeTags pt1 ON (pt1.tag.id = :mainTagId AND pt1.tag.type = 'MAIN')
        WHERE p.town.id = :townId
          AND (:mainTagId IS NULL OR pt1.tag.id IS NOT NULL)
          AND (
            :subTagOptionAIds IS NULL OR 
            SIZE(:subTagOptionAIds) = 0 OR 
            EXISTS (
                SELECT 1 FROM PlaceTag pt2
                WHERE pt2.place = p
                  AND pt2.tag.id IN :subTagOptionAIds
                  AND pt2.tag.type = 'OPTION1'
            )
          )
          AND (
            :subTagOptionBIds IS NULL OR 
            SIZE(:subTagOptionBIds) = 0 OR 
            EXISTS (
                SELECT 1 FROM PlaceTag pt3
                WHERE pt3.place = p
                  AND pt3.tag.id IN :subTagOptionBIds
                  AND pt3.tag.type = 'OPTION2'
            )
          )
        """)
    List<Place> findPlacesByTagConditions(
            @Param("townId") Long townId,
            @Param("mainTagId") Long mainTagId,
            @Param("subTagOptionAIds") List<Long> subTagOptionAIds,
            @Param("subTagOptionBIds") List<Long> subTagOptionBIds
    );


    @Query("SELECT p FROM Place p " +
            "JOIN FETCH p.town " +
            "WHERE p.id IN :placeIds")
    List<Place> findAllByIdsWithTown(@Param("placeIds") List<Long> placeIds);

    @Query("SELECT DISTINCT p FROM Place p " +
            "LEFT JOIN FETCH p.placeTags pt " +
            "LEFT JOIN FETCH pt.tag " +
            "WHERE p.town.id = :townId")
    List<Place> findPlacesByTownIdWithTags(@Param("townId") Long townId);
}
