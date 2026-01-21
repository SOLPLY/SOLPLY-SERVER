package org.sopt.solply_server.domain.place.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlaceReportStatus {
    PENDING("검토 대기"),
    RESOLVED("해결됨");

    private final String description;
}