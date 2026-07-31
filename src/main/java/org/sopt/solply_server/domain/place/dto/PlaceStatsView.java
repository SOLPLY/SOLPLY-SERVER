package org.sopt.solply_server.domain.place.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * place_stats의 요청 경로 읽기 모델. 엔티티(PlaceStats)는 배치 UPSERT 전용이라 생성자를
 * 봉인해 뒀다 — 읽기 경로와 테스트는 이 뷰만 만진다. 캐시(CachedPlace)에 싣지 않고 요청마다
 * 읽는 이유: 기존 캐시 구조의 존치 자체가 플랜 C 검증 대상이라 그 위에 점수를 얹지 않는다는
 * 결정(2026-07-31). 덕분에 정렬 신선도 상한이 "배치 24h + 캐시 1h"에서 "배치 24h"로 준다.
 */
public record PlaceStatsView(
        Long placeId,
        BigDecimal popularScore,
        int bookmarkCount,
        LocalDateTime calculatedAt
) {

    /** 정렬 키. 커서 sortKey(double)와 같은 표현 — DECIMAL(18,6) → double 변환은 여기 한 곳 */
    public double score() {
        return popularScore.doubleValue();
    }
}
