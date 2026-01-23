package org.sopt.solply_server.domain.place.repository;

import java.util.List;
import java.util.Optional;
import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaceRequestRepository extends JpaRepository<PlaceRequest, Long> {
    List<PlaceRequest> findAllByOrderByCreatedAtDesc();

    @Query("""
        select distinct pr
        from PlaceRequest pr
        join fetch pr.user u
        left join fetch pr.placeRequestTags prt
        left join fetch prt.tag t
        where pr.id = :id
    """)
    Optional<PlaceRequest> findByIdWithUserAndTags(Long id);
}
