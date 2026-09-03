package org.sopt.solply_server.domain.place.cache;

/**
 * 한 회차의 사진과 그것을 가리키는 <b>버전</b>. 커서가 이 버전을 싣고 다니며 다음 페이지가 같은
 * 회차에서 이어지게 한다.
 *
 * <p>버전은 {@link PlaceListSnapshotLoader#rebuild()}가 빌드를 끝낸
 * 시각({@code System.currentTimeMillis()})으로 <b>한 번만</b> 찍는다 — 발급 주체가 하나라 버전과
 * 내용이 1:1로 묶인다. 홀더({@link PlaceListSnapshot})는 다 만들어진 이 사진을 받기만 한다.
 * <b>인스턴스 로컬 값이고 DB에 아무것도 남지 않는다</b> — 다중 인스턴스에서의 한계는
 * {@link PlaceListSnapshot} 참조.
 *
 * @param version 이 사진의 식별자. 같은 인스턴스 안에서 회차마다 단조 증가한다
 * @param index   그 회차의 완결된 불변 인덱스
 */
public record PlaceListPhoto(long version, PlaceListIndex index) {
}
