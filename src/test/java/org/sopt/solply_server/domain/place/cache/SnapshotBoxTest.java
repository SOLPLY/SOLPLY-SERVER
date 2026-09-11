package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 홀더의 계약 — <b>최신 한 장만 든다</b>, <b>최신은 도착 순서가 아니라 버전이 정한다</b>, 그리고
 * <b>이미 잡아 간 참조는 교체와 무관하다</b>.
 *
 * <p>앞의 둘이 스크롤 세션의 수명을 정한다. 옛 회차를 남기지 않으므로 회차가 한 번 바뀌면 그 앞의
 * 커서는 만료다 — 그 판정 자체는 홀더가 아니라 조회 경로의 몫이고
 * ({@code PlaceService#requireCursorMatchesSnapshot}), 여기서는 <b>홀더가 옛 회차를 내주지
 * 않는다</b>는 것만 본다. 예전의 3장 보존을 걷어낸 근거는 {@code SnapshotBox} 계약 4다 — 어드민
 * 변경을 곧바로 보여주되 옛 회차로 스크롤을 이어 주지는 않는다는 정책이다.
 *
 * <p>마지막 하나가 그 단순화를 떠받친다. 홀더가 한 장만 들어도 <b>진행 중인 요청</b>이 반쪽 목록을
 * 보지 않는 이유는 보존이 아니라 스냅샷이 불변이라는 것이고, 그래서 그 성질을 값으로 못 박는다.
 *
 * <p><b>버전을 손으로 찍는 이유.</b> 운영에서 번호를 받아 스냅샷에 붙이는 것은 빌더 하나이고
 * ({@code SnapshotLoader#rebuild}) 홀더는 완성된 스냅샷을 받기만 한다. 그래서 여기서는
 * 발급소를 세울 것 없이 회차 번호를 직접 정해 넣는다 — 늦게 도착한 낡은 스냅샷처럼 실제 발급으로는
 * 만들기 어려운 순서까지 값으로 세울 수 있는 것이 이 방식의 요점이다.
 */
class SnapshotBoxTest {

    /**
     * 회차를 구분하는 표식 — 스냅샷마다 장소 수를 다르게 해 어느 회차의 스냅샷인지 값으로 드러낸다.
     * 정렬 배열이 불변이라 회차를 가리키는 다른 손잡이가 없다.
     */
    private static SortedPlaces sortedPlacesOfSize(int placeCount) {
        List<PlaceEntry> entries = new ArrayList<>(placeCount);
        for (int i = 1; i <= placeCount; i++) {
            entries.add(new PlaceEntry(
                    i, 1L, 0L,
                    1.0, 1_767_225_600L,
                    0L, 0L, 0,
                    null, null));
        }
        return SortedPlaces.of(entries);
    }

    /** 빌더가 하는 일 — 정렬 배열을 짓고 버전을 찍어 홀더에 넘긴다 */
    private static long adopt(SnapshotBox snapshotBox, long version, int placeCount) {
        snapshotBox.adopt(new Snapshot(version, sortedPlacesOfSize(placeCount)));
        return version;
    }

    /**
     * <b>채택은 최신을 갈아 끼우는 것이지 덧붙이는 것이 아니다.</b> {@code current}는 언제나 방금
     * 지은 스냅샷이어야 하고, 직전 회차는 그 자리에서 사라진다 — 장소 수를 함께 보는 것은 버전만
     * 갈리고 정렬 배열은 옛것이 남는 변이를 잡기 위해서다.
     */
    @Test
    void 채택하면_최신은_방금_지은_스냅샷이다() {
        SnapshotBox snapshotBox = new SnapshotBox();

        adopt(snapshotBox, 100L, 1);
        long second = adopt(snapshotBox, 200L, 2);

        assertThat(snapshotBox.current().version()).isEqualTo(second);
        assertThat(snapshotBox.current().sortedPlaces().placeCount()).isEqualTo(2);
    }

    /**
     * <b>늦게 도착한 낡은 스냅샷은 최신을 밀어내지 못한다.</b> 번호가 빌드를 <em>끝낸</em> 순서로
     * 발급되므로, 어드민 훅 빌드와 타이머 빌드가 겹치면 먼저 번호를 받은 스냅샷이 나중에 홀더에
     * 도착하는 역전이 생긴다. 가드가 없으면 그 한 번에 사용자가 보는 목록이 한 회차 뒤로 되감긴다.
     *
     * <p>한 장만 드는 지금은 <b>낡은 스냅샷이 아무 자리도 얻지 못한다</b> — 예전에는 그 버전을 든
     * 커서를 위해 보존 목록에 넣어 뒀지만, 지금은 최신이 아닌 회차의 커서가 어차피 만료라 넣을 이유가
     * 없다. 채택은 참조를 그대로 두는 것으로 끝난다.
     */
    @Test
    void 낡은_버전은_채택되지_않는다() {
        SnapshotBox snapshotBox = new SnapshotBox();
        long fresh = adopt(snapshotBox, 200L, 2);
        Snapshot held = snapshotBox.current();

        adopt(snapshotBox, 100L, 1);

        assertThat(snapshotBox.current()).as("참조까지 그대로다").isSameAs(held);
        assertThat(snapshotBox.current().version()).isEqualTo(fresh);
        assertThat(snapshotBox.current().sortedPlaces().placeCount()).isEqualTo(2);
    }

    /**
     * <b>같은 버전이 두 번 와도 먼저 든 것을 지킨다.</b> 버전 발급 주체가 빌더 하나라 같은 버전은
     * 정의상 같은 내용이고, 그래서 중복 도착을 그냥 통과시켜도 된다.
     *
     * <p>여기서 <b>다른 내용</b>을 같은 버전으로 밀어 넣는 것은 그 전제가 깨진 경우를 세우기
     * 위해서다 — 홀더가 뒤엣것으로 덮으면 같은 버전이 두 내용을 가리키게 되고, 커서가 박제한
     * "그 회차"라는 말의 뜻이 사라진다. 장수가 아니라 이 성질이 버전↔내용 1:1을 지킨다.
     */
    @Test
    void 같은_버전을_다시_채택해도_먼저_든_스냅샷이_남는다() {
        SnapshotBox snapshotBox = new SnapshotBox();
        long version = adopt(snapshotBox, 200L, 2);
        Snapshot held = snapshotBox.current();

        adopt(snapshotBox, version, 99);

        assertThat(snapshotBox.current()).isSameAs(held);
        assertThat(snapshotBox.current().sortedPlaces().placeCount()).isEqualTo(2);
    }

    /**
     * <b>이미 잡아 간 스냅샷은 채택 뒤에도 그대로다.</b> 한 장만 들고도 <em>진행 중인</em> 요청이
     * 두 회차가 섞인 목록을 볼 수 없는 이유가 오직 이것이다 ({@code SnapshotBox} 계약 2) —
     * 홀더는 참조를 갈아 끼울 뿐 내용을 고치지 않으므로, 응답을 만드는 도중 회차가 바뀌어도 그 요청은
     * 자기 회차를 끝까지 온전히 본다.
     *
     * <p>홀더에서 밀려난 것과 요청이 그것을 못 쓰게 되는 것은 다른 문제다 — 홀더가 이미 새 회차를
     * 가리킨 뒤에도 잡아 둔 쪽의 {@code placeCount}는 그대로여야 한다.
     */
    @Test
    void 잡아_간_스냅샷은_회차가_바뀌어도_그대로다() {
        SnapshotBox snapshotBox = new SnapshotBox();
        adopt(snapshotBox, 100L, 1);
        Snapshot held = snapshotBox.current();

        adopt(snapshotBox, 200L, 5);

        assertThat(held.version()).isEqualTo(100L);
        assertThat(held.sortedPlaces().placeCount()).as("잡아 둔 회차의 내용").isEqualTo(1);
        assertThat(snapshotBox.current()).as("홀더는 이미 새 회차다").isNotSameAs(held);
    }
}
