package org.sopt.solply_server.domain.place.dto;

import java.math.BigDecimal;

/**
 * place_stats의 요청 경로 읽기 모델. 엔티티(PlaceStats)는 배치 UPSERT 전용이라 생성자를
 * 봉인해 뒀다 — 읽기 경로와 테스트는 이 뷰만 만진다.
 *
 * <p><b>지금 이 뷰의 소비자는 북마크 검색 하나다.</b> 목록 경로는 정렬 쿼리가 점수와 카운트를
 * 함께 실어 오므로 뷰를 거치지 않는다. 캐시가 있던 시절에는 목록 경로도 이것을 읽었고,
 * 그때 "점수를 캐시에 얹지 않고 요청마다 읽는다"는 결정(2026-07-31)이 정렬 신선도의 상한을
 * "배치 + 캐시 hard TTL 1h"에서 배치 하나로 줄였다. 캐시가 사라져 그 항은 이제 존재하지 않는다.
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
     * <p>DECIMAL(18,6) → double 변환 지점은 시스템에 둘이다 — 여기(북마크 검색의 점수 정렬)와
     * {@code PlaceListDbQueryRepository#findPopularRows}의 {@code ((Number) row).doubleValue()}
     * (목록 경로의 정렬·커서). 둘 다 같은 {@code BigDecimal.doubleValue()}로 귀결되므로 같은
     * DECIMAL은 같은 double이 되고, 그 등가가 <b>두 경로의 인기순이 같은 순서를 내는 근거</b>다.
     * 한쪽만 변환 방식을 바꾸면 그 근거가 깨진다.
     */
    public double score() {
        return popularScore.doubleValue();
    }
}
