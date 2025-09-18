package org.sopt.solply_server.domain.place.repository;

import org.sopt.solply_server.domain.place.entity.PlaceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaceRequestRepository extends JpaRepository<PlaceRequest, Long> {

}
