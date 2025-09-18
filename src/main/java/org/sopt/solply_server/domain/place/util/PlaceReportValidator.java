package org.sopt.solply_server.domain.place.util;

import lombok.RequiredArgsConstructor;
import org.sopt.solply_server.domain.place.repository.PlaceReportRepository;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PlaceReportValidator {

    private final PlaceReportRepository placeReportRepository;

    public void validateReportLimits(Long userId, Long placeId) {
        LocalDateTime dayAgo = LocalDateTime.now().minusDays(1);

        boolean userExceedsLimit = placeReportRepository.existsByUserIdAndPlaceIdAndCreatedAtAfter(
                userId, placeId, dayAgo);

        if (userExceedsLimit) {
            throw new BusinessException(ErrorCode.REPORT_LIMIT_EXCEEDED_USER);
        }
    }

}