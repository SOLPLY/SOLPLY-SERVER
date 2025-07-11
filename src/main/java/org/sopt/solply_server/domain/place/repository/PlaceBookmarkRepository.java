package org.sopt.solply_server.domain.place.repository;

import org.sopt.solply_server.domain.place.entity.PlaceBookmark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface PlaceBookmarkRepository extends JpaRepository<PlaceBookmark, Long> {

    boolean existsByPlaceIdAndUserId(Long placeId, Long placeId1);

    @Query("SELECT pb.place.id FROM PlaceBookmark pb WHERE pb.user.id = :userId AND pb.place.id IN :placeIds")
    Set<Long> findBookmarkedPlaceIdsByUserIdAndPlaceIdIn(@Param("userId") Long userId, @Param("placeIds") List<Long> placeIds);
}
