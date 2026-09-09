package org.sopt.solply_server.domain.place.cache;

/**
 * 목록 한 항목의 <b>표시값</b> — 정렬·필터에 쓰이지 않고 화면에만 나가는 값들이다.
 * 정렬용 값({@link PlaceEntry})과 갈라져 있고, 사는 곳도 회차 스냅샷이 아니라
 * {@link PlaceViewHolder}다.
 *
 * <ul>
 *   <li><b>{@code imageUrl}은 이미 완성된 URL이다.</b> {@code fileKey}가 아니라
 *       {@code ImageUrlProvider.getImageUrl(fileKey)}의 결과를 <b>빌드 시점에</b> 담는다.
 *       CloudFront 도메인 + fileKey 문자열 결합이라 만료가 없어 미리 만들어도 안전하고,
 *       조회 경로에서 문자열 결합조차 하지 않는 것이 이 필드의 목적이다.
 *       썸네일이 없는 장소는 {@code null}이다(provider가 blank 키에 null을 낸다).</li>
 *   <li><b>{@code mainTagId}는 첫 MAIN 태그의 id이고 활성 여부를 묻지 않는다.</b> 비활성이라고
 *       여기서 비워 두면 안 된다 — 엔티티 경로는 "첫 MAIN 태그를 고른 뒤 비활성이면 이름을 null"
 *       이라, 비활성을 미리 거르면 <em>다음</em> MAIN 태그가 뽑혀 두 경로가 갈린다. 활성 판정은
 *       조회 시점에 {@link TagViewHolder}가 한다. MAIN 태그가 없으면 {@code null}.</li>
 * </ul>
 */
public record PlaceView(long placeId, String name, String imageUrl, Long mainTagId) {
}
