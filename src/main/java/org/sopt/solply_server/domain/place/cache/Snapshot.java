package org.sopt.solply_server.domain.place.cache;

/**
 * 한 회차의 스냅샷과 그것을 가리키는 <b>버전</b>. 커서가 이 버전을 싣고 다니며 다음 페이지가 같은
 * 회차에서 이어지게 한다.
 *
 * <p>버전은 {@link SnapshotLoader#rebuild()}가 빌드를 끝낸 뒤 DB 발급 테이블에서 받은
 * 번호({@link SnapshotVersionIssuer})를 <b>한 번만</b> 찍는다 — 발급 주체가 하나라 버전과
 * 내용이 1:1로 묶인다. 홀더({@link SnapshotBox})는 다 만들어진 이 스냅샷을 받기만 한다.
 * <b>번호는 공유 테이블에서 오지만 그 번호가 가리키는 스냅샷은 인스턴스의 힙에만 있다</b> —
 * 다중 인스턴스에서의 한계는 {@link SnapshotBox} 참조.
 *
 * <p><b>이 레코드가 불변인 것이 "요청은 처음 잡은 회차를 끝까지 본다"를 떠받친다.</b> 홀더가
 * 최신 한 장만 들고 회차마다 참조를 갈아 끼우므로, 진행 중인 요청이 옛 회차를 온전히 보는 근거는
 * 홀더의 보존이 아니라 <b>잡아 둔 이 참조의 내용이 바뀌지 않는다</b>는 것 하나뿐이다. 여기에
 * 나중에 값을 채워 넣는 필드가 생기면 그 근거가 무너진다.
 *
 * @param version      이 스냅샷의 식별자. 발급소가 하나라 회차마다 단조 증가한다
 * @param sortedPlaces 그 회차의 완결된 불변 정렬 배열
 */
public record Snapshot(long version, SortedPlaces sortedPlaces) {
}
