package org.sopt.solply_server.domain.place.cache;

/**
 * 목록 한 항목에서 <b>장소마다 변하지 않는 부분</b>만 떼어낸 표시 골격.
 *
 * <p>여기 있는 것과 없는 것이 이 record의 전부다.
 * <ul>
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
 *   <li><b>카운트·평점은 담지 않는다.</b> 정렬 쿼리(인기순·최신순)가 이미 같은 행에서 실어 온다.
 *       담으면 배치 주기와 다른 신선도를 가진 값이 두 벌 생긴다.</li>
 *   <li><b>{@code isBookmarked}는 담지 않는다.</b> 사용자별 값이라 장소 단위 캐시에 들어갈 수 없다.</li>
 * </ul>
 */
public record PlaceSkeleton(
        long id,
        String name,
        String imageUrl,
        String mainTagName,
        long townId
) {}
