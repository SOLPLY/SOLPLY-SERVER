package org.sopt.solply_server.domain.report.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlaceReportStatus {
    PENDING("검토 대기"),
    APPROVED("승인됨"),
    REJECTED("거부됨"),
    RESOLVED("해결됨");

    private final String description;
}