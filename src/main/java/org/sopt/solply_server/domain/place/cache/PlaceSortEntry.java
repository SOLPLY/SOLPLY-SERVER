package org.sopt.solply_server.domain.place.cache;

import java.math.BigDecimal;

/**
 * 장소 하나가 목록 정렬에 쓰는 값 전부 — {@code place_stats} 한 행 + {@code places}의 좌표 둘이다.
 *
 * <p>여기 있는 것과 없는 것이 이 record의 전부다.
 * <ul>
 *   <li><b>정렬 축 다섯이 전부 들어 있다</b> (점수·생성일·평점·리뷰 수·북마크 수) — 이 스냅샷의
 *       존재 이유가 "정렬을 DB에 묻지 않는 것"이라 축이 하나라도 빠지면 그 정렬만 DB로 새고,
 *       그러면 두 후보의 비교가 정렬별로 갈린다.</li>
 *   <li><b>{@code scored}는 {@code score_calculated_at}의 non-NULL 여부다.</b> 값 자체가 아니라
 *       "채점됐는가"만 남긴다 — 인기순의 술어가 묻는 것이 그것뿐이다. 이 칸이 없으면 미채점 행의
 *       {@code popular_score} 0이 <em>유효한 음수 점수</em> 위로 끼어든다
 *       ({@code PlaceListDbQueryRepository#findPopularRows} javadoc).</li>
 *   <li><b>{@code createdAtEpochSecond}는 UTC 간주 epoch 초다.</b> 커서가 싣는 값과 같은 식
 *       ({@code createdAt.toEpochSecond(ZoneOffset.UTC)})으로 <b>빌드 시점에</b> 좁혀 둔다 —
 *       {@code created_at}이 초 정밀도 DATETIME이라 정보가 상하지 않고, 조회 경로에서 시각 변환을
 *       하지 않는 것이 이 필드의 목적이다.</li>
 *   <li><b>평점이 두 벌인 것은 의도다.</b> {@code avgRating}(BigDecimal)은 응답에 그대로 실리는
 *       표시값이고 — DB 경로가 컬럼에서 읽어 오는 것과 <b>스케일까지</b> 같아야 응답이 바이트째
 *       같다 — {@code avgRatingValue}는 정렬·커서 비교용이다. 커서가 double 튜플이라 seek이
 *       double 공간에서 일어나고, MySQL도 DECIMAL과 DOUBLE 파라미터를 DOUBLE로 올려 비교하므로
 *       두 경로의 경계 판정이 같아진다.</li>
 *   <li><b>좌표는 null일 수 있다.</b> 거리를 잴 수 없는 장소이며, 거리순 후보에서 제외하는 규칙은
 *       DB 경로의 {@code p.latitude IS NOT NULL} 술어와 같다.</li>
 *   <li><b>이름·썸네일·대표 태그는 담지 않는다.</b> 그것은 {@link PlaceSkeleton}의 몫이고, 두
 *       스냅샷은 갱신 주기도 소비 지점도 다르다 — 한 record로 합치면 정렬 축이 바뀔 때마다 골격까지
 *       다시 짓게 된다.</li>
 * </ul>
 */
public record PlaceSortEntry(
        long placeId,
        long townId,
        long tagBitmask,
        double popularScore,
        boolean scored,
        long createdAtEpochSecond,
        long bookmarkCount,
        long reviewCount,
        BigDecimal avgRating,
        double avgRatingValue,
        Double latitude,
        Double longitude
) {

    /** 거리를 잴 수 있는 장소인가 — 좌표 둘이 모두 있어야 한다 */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
