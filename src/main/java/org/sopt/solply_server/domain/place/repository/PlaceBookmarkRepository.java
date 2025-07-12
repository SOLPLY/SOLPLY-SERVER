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

    List<Long> findPlaceIdsByUserId(Long userId);


    // 사용자의 모든 북마크 조회 (필요한 연관관계 모두 FETCH JOIN)
    @Query("SELECT pb FROM PlaceBookmark pb " +
            "JOIN FETCH pb.place p " +
            "JOIN FETCH p.town t " +
            "WHERE pb.user.id = :userId " +
            "ORDER BY pb.createdAt DESC")
    List<PlaceBookmark> findAllByUserId(@Param("userId") Long userId);

    // 특정 장소들의 북마크만 조회 (필요한 연관관계 모두 FETCH JOIN)
    @Query("SELECT pb FROM PlaceBookmark pb " +
            "JOIN FETCH pb.place p " +
            "JOIN FETCH p.town t " +
            "WHERE pb.user.id = :userId " +
            "AND pb.place.id IN :placeIds " +
            "ORDER BY pb.createdAt DESC")
    List<PlaceBookmark> findAllByUserIdAndPlaceIds(
            @Param("userId") Long userId,
            @Param("placeIds") List<Long> placeIds);

}
