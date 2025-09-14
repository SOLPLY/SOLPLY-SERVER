package org.sopt.solply_server.domain.report.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlaceReportType {
    STORE_CLOSED("폐업"), // 가게가 폐업했어요
    WRONG_ADDRESS("주소 오류"), // 잘못된 주소예요
    WRONG_PHONE("연락처 오류"), // 잘못된 전화번호예요
    WRONG_HOURS("영업시간 오류"), // 운영시간과 휴무일이 잘못되었어요
    WRONG_CATEGORY("카테고리 오류"), // 카테고리 분류가 잘못되었어요
    OTHER("기타"); // 기타

    private final String description;
}