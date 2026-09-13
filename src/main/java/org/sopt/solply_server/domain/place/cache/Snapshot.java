package org.sopt.solply_server.domain.place.cache;

/**
 * 한 회차의 스냅샷과 그것이 <b>어느 시점의 원본</b>인지를 말하는 번호 둘.
 *
 * <p><b>번호는 이 인스턴스가 정한 값이 아니다.</b> 리빌드가 읽기 트랜잭션의 첫 문장으로 읽어 온
 * {@code place_list_snapshot_metadata}의 값을 그대로 옮겨 담는다. 그래서 같은 시점의 원본을 읽은
 * 인스턴스들은 서로 다른 힙에 지었어도 같은 번호를 든다 — 커서가 인스턴스를 건너다녀도 통하는
 * 근거가 이것이다.
 *
 * <p><b>{@code revision}과 {@code cursorVersion}은 다른 것을 잰다.</b> 전자는 "다시 지을 것이
 * 있나"(폴이 보는 값), 후자는 "커서를 계속 써도 되나"(커서가 싣는 값)다. 표시값만 바뀐 회차나
 * 어드민이 스크롤 유지를 고른 회차는 revision만 오르고 cursorVersion은 그대로다.
 *
 * <p><b>이 레코드가 불변인 것이 "요청은 처음 잡은 회차를 끝까지 본다"를 떠받친다.</b> 홀더가 최신
 * 한 장만 들고 회차마다 참조를 갈아 끼우므로, 진행 중인 요청이 옛 회차를 온전히 보는 근거는
 * 홀더의 보존이 아니라 <b>잡아 둔 이 참조의 내용이 바뀌지 않는다</b>는 것 하나뿐이다. 여기에
 * 나중에 값을 채워 넣는 필드가 생기면 그 근거가 무너진다.
 *
 * @param revision      이 스냅샷이 읽은 원본 시점. 설치의 단조 가드가 보는 값
 * @param cursorVersion 커서가 싣고 다니는 회차. revision보다 드물게 오른다
 * @param sortedPlaces  그 시점의 완결된 불변 정렬 배열
 */
public record Snapshot(long revision, long cursorVersion, SortedPlaces sortedPlaces) {
}
