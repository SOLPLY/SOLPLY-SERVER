package org.sopt.solply_server.domain.place.repository;

import java.util.List;
import org.sopt.solply_server.domain.place.entity.PlaceBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface PlaceBookmarkRepository extends JpaRepository<PlaceBookmark, Long> {

    boolean existsByPlaceIdAndUserId(Long placeId, Long placeId1);

    @Query("SELECT pb.place.id FROM PlaceBookmark pb WHERE pb.user.id = :userId AND pb.place.id IN :placeIds")
    Set<Long> findBookmarkedPlaceIdsByUserIdAndPlaceIds(@Param("userId") Long userId, @Param("placeIds") List<Long> placeIds);

    boolean existsByUserIdAndPlaceId(Long userId, Long placeId);

    void deleteByUserIdAndPlaceId(Long userId, @Param("placeId") Long placeId);
}
