package org.sopt.solply_server.domain.review.repository;

import java.util.List;
import org.sopt.solply_server.domain.review.entity.PlaceReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceReviewRepository extends JpaRepository<PlaceReview, Long> {

  @Query("""
      select distinct pr
      from PlaceReview pr
      join fetch pr.user u
      left join fetch pr.placeReviewImages pri
      where pr.place.id = :placeId
      order by pr.createdAt desc
      """)
  List<PlaceReview> findAllByPlaceIdOrderByCreatedAtDesc(@Param("placeId") Long placeId);
  List<PlaceReview> findTop4ByPlaceIdOrderByCreatedAtDesc(Long placeId);

  @Query("""
    select distinct pr
    from PlaceReview pr
    join fetch pr.place p
    left join fetch pr.placeReviewImages pri
    where pr.user.id = :userId
    order by pr.createdAt desc
    """)
  List<PlaceReview> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
  List<PlaceReview> findTop4ByUserIdOrderByCreatedAtDesc(Long userId);
}