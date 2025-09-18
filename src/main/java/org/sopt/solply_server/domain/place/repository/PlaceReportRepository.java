package org.sopt.solply_server.domain.place.repository;

import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PlaceReportRepository extends JpaRepository<PlaceReport, Long> {

    List<PlaceReport> findByPlaceIdAndStatus(Long placeId, PlaceReportStatus status);

    List<PlaceReport> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 사용자가 특정 장소에 특정 시간 이후 신고했는지 확인 (중복 신고 방지)
    boolean existsByUserIdAndPlaceIdAndCreatedAtAfter(Long userId, Long placeId, LocalDateTime since);
}