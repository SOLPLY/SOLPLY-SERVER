package org.sopt.solply_server.domain.place.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlaceRequestStatus {
    PENDING("검토 대기"),
    APPROVED("승인"),
    REJECTED("반려");

    private final String description;
}