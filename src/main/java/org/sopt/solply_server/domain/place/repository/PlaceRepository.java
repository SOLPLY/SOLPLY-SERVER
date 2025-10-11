package org.sopt.solply_server.domain.place.repository;

import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceRepositoryCustom;
import org.sopt.solply_server.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query("SELECT p FROM Place p WHERE p.id IN :placeIds")
    List<Place> findByIdIn(@Param("placeIds") List<Long> placeIds);

    List<Place> findTop3ByCreatedByOrderByCreatedAtDesc(User createdBy);

}
