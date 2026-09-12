package org.sopt.solply_server.domain.place.cache;

/**
 * 한 회차의 스냅샷과 그것을 가리키는 <b>버전</b>. 커서가 이 버전을 싣고 다니며 다음 페이지가 같은
 * 회차에서 이어지게 한다.
 *
 * <p><b>버전은 발행물이 실어 온 값이지 이 인스턴스가 정한 값이 아니다.</b>
 * {@code place_list_publications.cursor_version}을 {@link SnapshotInstaller}가 그대로 옮겨
 * 담는다 — 그래서 같은 발행물을 복원한 인스턴스들이 같은 회차를 들고, 배포로 올라온 인스턴스도
 * 회차를 갈아치우지 않는다.
 *
 * <p><b>발행마다 오르는 것은 아니다.</b> 표시값만 바뀐 발행은 직전 회차를 그대로 이어받으므로
 * 진행 중인 커서가 끊기지 않는다. "새 내용이 있나"를 재는 값은 따로 있다(발행 id).
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
