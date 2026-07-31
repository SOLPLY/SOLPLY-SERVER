package org.sopt.solply_server.domain.place.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * place_stats의 요청 경로 읽기 모델. 엔티티(PlaceStats)는 배치 UPSERT 전용이라 생성자를
 * 봉인해 뒀다 — 읽기 경로와 테스트는 이 뷰만 만진다. 캐시(CachedPlace)에 싣지 않고 요청마다
 * 읽는 이유: 기존 캐시 구조의 존치 자체가 플랜 C 검증 대상이라 그 위에 점수를 얹지 않는다는
 * 결정(2026-07-31).
 *
 * <p><b>아직 아무도 이 뷰를 소비하지 않는다.</b> 이 타입이 노리는 효과 — 정렬 신선도 상한이
 * "배치 24h + 캐시 1h"에서 "배치 24h"로 주는 것 — 는 요청 경로가 {@code CachedPlace.popularScore}
 * 대신 이 뷰를 읽도록 바뀐 뒤에야 실현된다. 그 전까지 상한은 여전히 캐시 hard TTL을 포함한다.
 */
public record PlaceStatsView(
        Long placeId,
        BigDecimal popularScore,
        int bookmarkCount,
        LocalDateTime calculatedAt
) {

    /**
     * 정렬 키. 커서 sortKey(double)와 같은 표현이다.
     *
     * <p>DECIMAL(18,6) → double 변환 지점은 <b>현재 두 곳</b>이다 — 여기와
     * {@code TownPlacesSnapshotLoader}(스냅샷에 점수를 실을 때). {@code CachedPlace}에서
     * 점수·카운트 필드가 빠지면 후자가 사라지고 이 메서드가 유일한 변환 지점이 된다.
     */
    public double score() {
        return popularScore.doubleValue();
    }
}
