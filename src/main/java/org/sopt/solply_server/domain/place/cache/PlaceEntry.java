package org.sopt.solply_server.domain.place.cache;

/**
 * 목록 한 항목의 <b>정렬·필터·거리 값</b>. 출처는 {@code place_stats} 한 행 + {@code places}의
 * 좌표다. 화면에 그리는 값(이름·썸네일·대표 태그)은 여기 없고 {@link PlaceView}에 있다.
 *
 * <p><b>그 분리가 이 타입의 계약이다.</b> 회차 스냅샷이 박제하는 것은 <b>순서</b>뿐이며, 표시값은
 * 스냅샷 밖 홀더({@link PlaceViewHolder}·{@link TagViewHolder})에 살면서 어드민 수정마다 그
 * 항목만 갈린다. 그래서 한 응답이 "옛 회차의 순서 + 지금의 표시값"으로 조립되는 것이 정상이다 —
 * 이름 하나 고치자고 전량을 다시 짓지 않기 위해 받아들인 계약이다.
 *
 * <p>여기 있는 것과 없는 것이 이 record의 전부다.
 * <ul>
 *   <li><b>정렬 축 다섯이 전부 들어 있다</b> (점수·생성일·평점·리뷰 수·북마크 수) — 이 스냅샷의
 *       존재 이유가 "정렬을 DB에 묻지 않는 것"이라 축이 하나라도 빠지면 그 정렬만 DB로 새고,
 *       그러면 두 후보의 비교가 정렬별로 갈린다.</li>
 *   <li><b>{@code tagBitmask}와 좌표가 여기 남는 것은 원소마다 읽히기 때문이다.</b> 태그 필터는
 *       스캔하는 전 원소에, 거리 계산은 후보 전량에 걸린다 — 홀더 조회로 미루면 그 뜨거운 루프가
 *       맵 조회로 바뀐다. 표시값은 반대로 페이지에 실린 열 몇 개에만 필요하다.</li>
 *   <li><b>채점 여부는 담지 않는다.</b> 미채점은 점수 0으로 그 값 위치에 정렬된다 — 채점 여부는
 *       목록 경로의 관심사가 아니다(스펙 결정 2026-09-01).</li>
 *   <li><b>{@code createdAtEpochSecond}는 UTC 간주 epoch 초다.</b> 커서가 싣는 값과 같은 식
 *       ({@code createdAt.toEpochSecond(ZoneOffset.UTC)})으로 <b>빌드 시점에</b> 좁혀 둔다 —
 *       {@code created_at}이 초 정밀도 DATETIME이라 정보가 상하지 않고, 조회 경로에서 시각 변환을
 *       하지 않는 것이 이 필드의 목적이다.</li>
 *   <li><b>평점은 정수 한 벌이다.</b> {@code avg_rating}이 DECIMAL(3,2)라 자리 수가 상수이므로
 *       {@code ratingToInt}은 무척도 정수(unscaled)만 든다({@code 4.50} → {@code 450}). 비교는 정수끼리 하고,
 *       응답에 실을 때만 {@code BigDecimal.valueOf(ratingToInt, 2)}로 {@code 4.50}을 복원해 DB
 *       경로가 컬럼에서 읽어 오는 것과 <b>스케일까지</b> 같은 값을 낸다. 커서는 double 튜플이라
 *       평점도 double로 실리지만, 안에서 {@code Math.round(v × 100)}으로 정수를 되찾아 비교한다 —
 *       원값이 백분의 일 단위라 그 왕복에 오차가 없다. 카운트·평점이 표시값이면서도 홀더로 가지
 *       않는 것은 이들이 <b>정렬 축</b>이라 순서와 한 회차로 묶여야 하기 때문이다.</li>
 *   <li><b>좌표는 null일 수 있다.</b> 거리를 잴 수 없는 장소이며, 거리순 후보에서 제외하는 규칙은
 *       DB 경로의 {@code p.latitude IS NOT NULL} 술어와 같다. 0으로 채우면 기니만 앞바다가 실재
 *       좌표라 "좌표 없음"과 섞인다.</li>
 *   <li><b>{@code isBookmarked}는 담지 않는다.</b> 사용자별 값이라 장소 단위 캐시에 들어갈 수 없다.</li>
 * </ul>
 */
public record PlaceEntry(
        long placeId,
        long townId,
        long tagBitmask,
        double popularScore,
        long createdAtEpochSecond,
        long bookmarkCount,
        long reviewCount,
        int ratingToInt,
        Double latitude,
        Double longitude
) {

    /** 거리를 잴 수 있는 장소인가 — 좌표 둘이 모두 있어야 한다 */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    /**
     * 어드민 수정 한 건을 입힌 엔트리 — <b>최신 행에서 가져오는 것은 동네·태그 비트마스크·좌표뿐이고,
     * 정렬 키 다섯(점수·생성일·북마크 수·리뷰 수·평점)은 이 회차 값을 그대로 지킨다.</b>
     *
     * <p>정렬 키는 회차 주기의 전량 재빌드로만 공표되는 값이다. 어드민이 태그 하나를 고치는 사이에도
     * DB의 점수·카운트는 계속 흐르는데, 그 최신값을 여기서 끌어오면 <b>손댄 장소만</b> 새 값으로,
     * 나머지 전량은 옛 값으로 서게 되어 한 회차의 순서가 장소마다 다른 기준으로 갈린다. 지금 값을
     * 지키면 그 회차의 순서는 끝까지 한 벌이고, 다음 재빌드에서 전량이 함께 새 값으로 옮겨 간다.
     *
     * <p>그 덕에 {@code SortedPlaces#patch}의 같은 동네 수정이 <b>정렬 없이</b> 끝난다 — 순서를
     * 정하는 값이 하나도 안 바뀌었으니 새 엔트리의 자리가 옛 엔트리의 자리와 같다.
     */
    public PlaceEntry patchedBy(PlaceEntry latest) {
        return new PlaceEntry(
                placeId, latest.townId(), latest.tagBitmask(),
                popularScore, createdAtEpochSecond,
                bookmarkCount, reviewCount, ratingToInt,
                latest.latitude(), latest.longitude());
    }
}
