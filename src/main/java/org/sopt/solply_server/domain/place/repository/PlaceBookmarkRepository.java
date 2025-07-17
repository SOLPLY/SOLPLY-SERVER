package org.sopt.solply_server.domain.place.repository;

import org.sopt.solply_server.domain.place.entity.PlaceBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceBookmarkRepository extends JpaRepository<PlaceBookmark, Long> {

    boolean existsByPlaceIdAndUserId(Long placeId, Long placeId1);

    void deleteByUserIdAndPlaceId(Long userId, @Param("placeId") Long placeId);

    boolean existsByUserIdAndPlaceId(Long userId, Long placeId);

//    @Modifying
//    @Query(value = "INSERT INTO place_bookmark (user_id, place_id, created_at, updated_at) " +
//            "VALUES (:userId, :placeId, NOW(), NOW()) " +
//            "ON CONFLICT (user_id, place_id) DO NOTHING",
//            nativeQuery = true)
//    void upsertBookmark(@Param("userId") Long userId, @Param("placeId") Long placeId);
}
