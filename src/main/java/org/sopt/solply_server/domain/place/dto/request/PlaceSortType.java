package org.sopt.solply_server.domain.place.dto.request;

/** 장소 리스트 정렬 기준. 미지정 시 LATEST (기존 동작 유지) */
public enum PlaceSortType {
    LATEST,
    POPULAR
}
