package org.sopt.solply_server.domain.place.cache;

/**
 * 태그 하나의 표시값. 목록이 태그에서 읽는 것은 이름과 활성 여부 둘뿐이다.
 *
 * <p><b>비활성 태그도 담는다.</b> 대표 태그 이름을 비우는 판정
 * ({@code TagViewUtils.getActiveNameOrNull})이 조회 시점에 이 {@code active}로 이뤄지므로,
 * 비활성이라고 맵에서 빼면 "태그가 사라진 것"과 "비활성인 것"이 구분되지 않는다.
 */
public record TagView(long tagId, String name, boolean active) {
}
