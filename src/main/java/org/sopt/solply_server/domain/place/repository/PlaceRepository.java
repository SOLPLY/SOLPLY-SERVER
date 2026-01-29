package org.sopt.solply_server.domain.place.repository;

import io.lettuce.core.dynamic.annotation.Param;
import software.amazon.awssdk.services.s3.endpoints.internal.Value;

import java.util.List;
import java.util.Optional;

import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceRepositoryCustom;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlaceRepository extends JpaRepository<Place, Long>, PlaceRepositoryCustom {

    @Query("SELECT p FROM Place p " +
            "JOIN FETCH p.town " +
            "WHERE p.id IN :placeIds")
    List<Place> findAllByIdsWithTown(@Param("placeIds") List<Long> placeIds);

    @Query("SELECT DISTINCT p FROM Place p " +
            "LEFT JOIN FETCH p.placeTags pt " +
            "LEFT JOIN FETCH pt.tag " +
            "WHERE p.town.id = :townId")
    List<Place> findPlacesByTownIdWithTags(@Param("townId") Long townId);

    @EntityGraph(attributePaths = {
            "placeTags",
            "placeTags.tag"
    })
    List<Place> findTop3ByCreatedByOrderByCreatedAtDesc(User createdBy);

    @Query("""
        select p.id, p.town.id
        from Place p
        where p.id in :placeIds
    """)
    List<Object[]> findPlaceIdAndTownIdByPlaceIds(@Param("placeIds") List<Long> placeIds);

    @Query("""
        select distinct p
        from Place p
        left join fetch p.placeTags pt
        left join fetch pt.tag t
        where p.id in :placeIds
    """)
    List<Place> findByIdInWithTags(List<Long> placeIds);

    @Query("""
        select p
        from Place p
        join fetch p.town
        where p.id = :placeId
    """)
    Optional<Place> findByIdWithTown(@Param("placeId") Long placeId);
}
