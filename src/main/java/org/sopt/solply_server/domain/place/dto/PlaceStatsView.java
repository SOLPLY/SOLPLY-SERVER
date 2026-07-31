package org.sopt.solply_server.domain.place.dto;

import java.math.BigDecimal;

/**
 * place_stats의 요청 경로 읽기 모델. 엔티티(PlaceStats)는 배치 UPSERT 전용이라 생성자를
 * 봉인해 뒀다 — 읽기 경로와 테스트는 이 뷰만 만진다. 캐시(CachedPlace)에 싣지 않고 요청마다
 * 읽는 이유: 기존 캐시 구조의 존치 자체가 플랜 C 검증 대상이라 그 위에 점수를 얹지 않는다는
 * 결정(2026-07-31).
 *
 * <p>{@code PlaceService.getPlaces}가 정렬 점수와 표시 카운트를 모두 이 뷰에서 얻는다.
 * 그 결과 정렬 신선도의 상한이 "배치 24h + 캐시 hard TTL 1h"에서 <b>"배치 24h"</b> 하나로 줄었다.
 * 카운트 쪽은 이벤트 증분이 붙어 그보다 훨씬 신선하다.
 *
 * <p><b>{@code calculated_at}은 싣지 않는다.</b> 표시 카운트 보정이 그 시각을 내 북마크 시각과
 * 비교하느라 필요했는데, 증분 도입으로 보정이 사라지면서(2026-07-31) 읽는 코드가 없어졌다.
 * 컬럼 자체는 "마지막 배치 정산 시각"으로 테이블에 남아 있다 — 운영 관측용이다.
 */
public record PlaceStatsView(
        Long placeId,
        BigDecimal popularScore,
        int bookmarkCount
) {

    /**
     * 정렬 키. 커서 sortKey(double)와 같은 표현이다.
     *
     * <p>DECIMAL(18,6) → double 변환 지점은 <b>캐시 경로에서는 여기 하나뿐</b>이다
     * ({@code CachedPlace}에서 점수·카운트 필드가 빠지면서 스냅샷 로더 쪽 변환이 사라졌다).
     * db 경로에는 짝이 되는 변환이 하나 더 있다 —
     * {@code PlaceListDbQueryRepository#findPopularRows}의 {@code ((Number) row).doubleValue()}.
     * 두 지점은 같은 {@code BigDecimal.doubleValue()}로 귀결되므로 같은 DECIMAL은 같은 double이 되고,
     * 그 등가가 <b>두 모드의 커서가 호환되는 근거</b>다. 한쪽만 변환 방식을 바꾸면 그 근거가 깨진다.
     */
    public double score() {
        return popularScore.doubleValue();
    }
}
