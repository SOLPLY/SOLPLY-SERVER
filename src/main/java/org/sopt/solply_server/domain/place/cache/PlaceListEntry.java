package org.sopt.solply_server.domain.place.cache;

import java.math.BigDecimal;

/**
 * 목록 한 항목이 필요로 하는 값 <b>전부</b> — 정렬에 쓰는 값과 화면에 그리는 값이 한 행에 같이 있다.
 * 출처는 {@code place_stats} 한 행 + {@code places}의 좌표·이름 + 메인 태그 + 썸네일이다.
 *
 * <p><b>평면 record인 것이 이 타입의 계약이다.</b> 정렬용·표시용을 중첩 그룹으로 나누지 않는다 —
 * 나누면 두 묶음이 서로 다른 회차의 값을 들 수 있는 모양이 되고, 그 순간 "한 회차의 사진"이라는
 * 스냅샷의 전제가 깨진다. 한 행은 통째로 한 회차에서 나온다.
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
 *       DB 경로의 {@code p.latitude IS NOT NULL} 술어와 같다. 0으로 채우면 기니만 앞바다가 실재
 *       좌표라 "좌표 없음"과 섞인다.</li>
 *   <li><b>{@code imageUrl}은 이미 완성된 URL이다.</b> {@code fileKey}가 아니라
 *       {@code ImageUrlProvider.getImageUrl(fileKey)}의 결과를 <b>빌드 시점에</b> 담는다.
 *       CloudFront 도메인 + fileKey 문자열 결합이라 만료가 없어 미리 만들어도 안전하고,
 *       조회 경로에서 문자열 결합조차 하지 않는 것이 이 필드의 목적이다.
 *       썸네일이 없는 장소는 {@code null}이다(provider가 blank 키에 null을 낸다).</li>
 *   <li><b>{@code mainTagName}은 {@code TagViewUtils.getActiveNameOrNull} 규칙과 같아야 한다</b> —
 *       메인 태그가 없거나 그 태그가 비활성이면 {@code null}. 비활성 태그를 빌드 쿼리에서
 *       걸러내면 안 된다: 엔티티 경로는 "첫 MAIN 태그를 고른 뒤 비활성이면 null"이라
 *       비활성 MAIN이 붙은 장소에서 두 경로가 갈린다(비활성을 미리 거르면 <em>다음</em> MAIN
 *       태그가 뽑힌다).</li>
 *   <li><b>{@code isBookmarked}는 담지 않는다.</b> 사용자별 값이라 장소 단위 캐시에 들어갈 수 없다.</li>
 * </ul>
 */
public record PlaceListEntry(
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
        Double longitude,
        String name,
        String imageUrl,
        String mainTagName
) {

    /** 거리를 잴 수 있는 장소인가 — 좌표 둘이 모두 있어야 한다 */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
