package org.sopt.solply_server.domain.place.cache;

/**
 * 목록 한 항목의 <b>표시값</b> — 정렬·필터에 쓰이지 않고 화면에만 나가는 값들이다.
 * 정렬용 값({@link PlaceEntry})과 갈라져 있고, 사는 곳도 회차 스냅샷이 아니라
 * {@link PlaceViewHolder}다.
 *
 * <ul>
 *   <li><b>{@code thumbnailFileKey}는 완성된 URL이 아니라 {@code image_file_key} 원값이다.</b>
 *       URL 결합({@code ImageUrlProvider.getImageUrl})은 조회 경로로 미룬다 — 재빌드는 전 장소의
 *       URL 문자열을 만들어야 하지만 그중 실제로 쓰이는 것은 응답 페이지에 실리는 열 몇 건뿐이라,
 *       나머지는 만들자마자 버려지는 문자열이다. 썸네일이 없는 장소는 {@code null}이고, 빈 키는
 *       빈 키 그대로 담는다 — provider가 blank에 null을 내므로 응답은 어느 쪽이든 {@code null}이다.</li>
 *   <li><b>{@code mainTagId}는 첫 MAIN 태그의 id이고 활성 여부를 묻지 않는다.</b> 비활성이라고
 *       여기서 비워 두면 안 된다 — 엔티티 경로는 "첫 MAIN 태그를 고른 뒤 비활성이면 이름을 null"
 *       이라, 비활성을 미리 거르면 <em>다음</em> MAIN 태그가 뽑혀 두 경로가 갈린다. 활성 판정은
 *       조회 시점에 {@link TagViewHolder}가 한다. MAIN 태그가 없으면 {@code null}.</li>
 * </ul>
 */
public record PlaceView(long placeId, String name, String thumbnailFileKey, Long mainTagId) {
}
