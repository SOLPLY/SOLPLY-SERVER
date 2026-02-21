package org.sopt.solply_server.domain.place.repository;

import java.util.List;
import java.util.Optional;

import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceRepositoryCustom;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceRepository extends JpaRepository<Place, Long>, PlaceRepositoryCustom {

    @Query("""
        SELECT p
        FROM Place p
        JOIN FETCH p.town
        WHERE p.id IN :placeIds
          AND p.active = true
    """)
    List<Place> findAllByIdsWithTown(@Param("placeIds") List<Long> placeIds);

    @Query("""
        SELECT DISTINCT p
        FROM Place p
        LEFT JOIN FETCH p.placeTags pt
        LEFT JOIN FETCH pt.tag
        WHERE p.town.id = :townId
          AND p.active = true
    """)
    List<Place> findPlacesByTownIdWithTags(@Param("townId") Long townId);

    @EntityGraph(attributePaths = {
            "placeTags",
            "placeTags.tag"
    })
    List<Place> findTop3ByCreatedByAndActiveTrueOrderByCreatedAtDesc(User createdBy);

    @Query("""
        SELECT p.id, p.town.id
        FROM Place p
        WHERE p.id IN :placeIds
          AND p.active = true
    """)
    List<Object[]> findPlaceIdAndTownIdByPlaceIds(@Param("placeIds") List<Long> placeIds);

    @Query("""
        SELECT DISTINCT p
        FROM Place p
        LEFT JOIN FETCH p.placeTags pt
        LEFT JOIN FETCH pt.tag t
        WHERE p.id IN :placeIds
          AND p.active = true
    """)
    List<Place> findByIdInWithTags(@Param("placeIds") List<Long> placeIds);

    @Query("""
        select distinct p
        from Place p
        left join fetch p.town t
        left join fetch p.checkpoints cp
        where p.id = :placeId
    """)
    Optional<Place> findByIdWithTownAndCheckpoints(@Param("placeId") Long placeId);
}
