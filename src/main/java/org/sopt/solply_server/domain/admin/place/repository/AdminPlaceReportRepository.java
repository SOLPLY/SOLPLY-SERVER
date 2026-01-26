package org.sopt.solply_server.domain.admin.place.repository;

import java.util.List;
import java.util.Optional;
import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdminPlaceReportRepository extends JpaRepository<PlaceReport, Long> {

    @Query("""
        select pr
        from PlaceReport pr
        join fetch pr.place p
        where pr.status = :status
        order by pr.createdAt desc
       """)
    List<PlaceReport> findAllWithPlaceByStatusOrderByCreatedAtDesc(PlaceReportStatus status);

    @Query("""
            select pr
            from PlaceReport pr
            join fetch pr.place p
            where pr.id = :reportId
           """)
    Optional<PlaceReport> findByIdWithPlace(Long reportId);
}
