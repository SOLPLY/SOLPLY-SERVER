package org.sopt.solply_server.domain.place.dto;

import java.math.BigDecimal;

/**
 * place_stats의 요청 경로 읽기 모델. 엔티티(PlaceStats)는 배치 UPSERT 전용이라 생성자를
 * 봉인해 뒀다 — 읽기 경로와 테스트는 이 뷰만 만진다.
 *
 * <p><b>지금 이 뷰의 소비자는 북마크 검색 하나다.</b> 목록 경로는 정렬 쿼리가 점수와 표시값을
 * 함께 실어 오므로 뷰를 거치지 않는다. 두 경로의 표시값이 같은 컬럼에서 와야 하므로 한쪽에
 * 표시 항목을 더하면 다른 쪽도 함께 늘린다. 값은 <b>현 버전</b>의 행에서 온다 —
 * 어느 버전인지는 {@code PlaceStatsRepository#findViewsByPlaceIds}가 스칼라 서브쿼리로 지목한다.
 *
 */
public record PlaceStatsView(
        Long placeId,
        BigDecimal popularScore,
        int bookmarkCount,
        int reviewCount,
        BigDecimal avgRating
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
