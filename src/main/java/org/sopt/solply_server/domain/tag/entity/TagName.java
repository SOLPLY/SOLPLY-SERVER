package org.sopt.solply_server.domain.tag.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TagName {

    // ===== 메인 태그 =====
    CAFE("카페"),
    FOOD("음식"),
    SHOPPING("쇼핑"),
    BOOKSTORE("책방/서점"),
    UNIQUE_SPACE("이색공간"),
    WALKING("산책"),

    // ===== 카페 추천 옵션 1 =====
    COFFEE_DESSERT("커피/디저트"),
    WORK("작업"),
    READING("독서"),
    HEALING("힐링"),

    // ===== 카페 추천 옵션 2 =====
    SIGNATURE_MENU("시그니처메뉴"),
    MOOD_INTERIOR("감성인테리어"),
    SUNLIGHT("채광좋음"),
    MANY_PLUG("콘센트많음"),
    NO_TIME_LIMIT("시간제한없음"),
    BAR_TABLE("바테이블"),

    // ===== 음식 추천 옵션 1 =====
    KOREAN("한식"),
    CHINESE("중식"),
    JAPANESE("일식"),
    WESTERN("양식"),
    BAR("바/술집"),
    BAKERY("베이커리"),
    ASIAN("아시안푸드"),

    // ===== 음식 추천 옵션 2 =====
    SINGLE_MENU("1인메뉴"),
    SELF_SERVICE("셀프서비스"),

    // ===== 이색공간 추천 옵션 =====
    ART("문화예술"),
    WORKSHOP("공방/클래스"),

    // ===== 쇼핑 추천 옵션 =====
    LIFESTYLE_SHOP("소품샵"),
    VINTAGE_SHOP("빈티지샵");

    private final String displayName;
}