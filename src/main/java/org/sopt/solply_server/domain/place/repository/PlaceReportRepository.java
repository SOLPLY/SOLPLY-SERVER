package org.sopt.solply_server.domain.place.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.sopt.solply_server.domain.place.entity.PlaceReport;
import org.sopt.solply_server.domain.place.entity.PlaceReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlaceReportRepository extends JpaRepository<PlaceReport, Long> {


    // 사용자가 특정 장소에 특정 시간 이후 신고했는지 확인 (중복 신고 방지)
    boolean existsByUserIdAndPlaceIdAndCreatedAtAfter(Long userId, Long placeId, LocalDateTime since);

}