package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.TagMasks;

/**
 * 인덱스의 <b>전 원소 불변식</b> — 정렬 다섯은 순서만 정하고 원소를 걸러내지 않는다.
 *
 * <p>이 불변식이 거리순 후보 공급의 근거다. 거리순은 기준점이 요청마다 달라 미리 세워 둘 순서가
 * 없고, 그래서 {@code PlaceListIndex#distanceCandidates}는 정렬 배열 하나를 빌려 훑는다
 * ({@code DISTANCE_SOURCE}). 그 배열이 동네의 전 원소일 때만 성립하는 방식이다.
 *
 * <p><b>이 테스트가 빨개진다면 어떤 정렬에 포함 규칙이 생긴 것이다.</b> 그때 할 일은 기대값을
 * 고치는 것이 아니라 거리순의 후보 집합을 정렬 축과 <b>독립된 별도 묶음</b>으로 되돌리는 것이다
 * (동네 → 전 후보 맵의 부활 지점). 그러지 않으면 그 규칙이 거리순 결과까지 조용히 좁힌다.
 */
class PlaceListIndexTest {

    private static final TagMasks NO_FILTER = TagMasks.of(null, null, null);
    private static final long TOWN_A = 1L;
    private static final long TOWN_B = 2L;

    /**
     * 어떤 정렬이 원소를 걸러낸다면 걸릴 만한 자리를 일부러 섞어 둔다 — 미채점(점수 0), 리뷰 0건,
     * 좌표 없음. 셋 다 "제외하고 싶어질" 후보였던 값이라, 그 규칙이 다시 들어오면 여기서 잡힌다.
     */
    private static PlaceListIndex fixture() {
        List<PlaceListEntry> entries = new ArrayList<>();
        entries.add(entry(1L, TOWN_A, 0.0, 0L, 0L, null));      // 미채점 + 리뷰 0건
        entries.add(entry(2L, TOWN_A, 12.5, 7L, 3L, 37.5));
        entries.add(entry(3L, TOWN_A, -4.0, 0L, 1L, null));     // 음수 점수 + 좌표 없음
        entries.add(entry(4L, TOWN_A, 3.0, 2L, 9L, 37.6));
        entries.add(entry(5L, TOWN_B, 0.0, 0L, 0L, 37.7));
        entries.add(entry(6L, TOWN_B, 8.0, 5L, 2L, null));
        return PlaceListIndex.of(entries);
    }

    private static PlaceListEntry entry(long placeId, long townId, double popularScore,
            long reviewCount, long bookmarkCount, Double latitude) {
        return new PlaceListEntry(
                placeId, townId, 0L,
                popularScore, 1_767_225_600L + placeId,
                bookmarkCount, reviewCount, BigDecimal.ZERO, reviewCount == 0 ? 0.0 : 4.0,
                latitude, latitude == null ? null : 127.0,
                "장소" + placeId, null, null);
    }

    /** 정렬 하나가 그 동네에서 내놓는 전량 — 커서 없이 끝까지 훑는다 */
    private static List<PlaceListEntry> all(PlaceListIndex index, PlaceSortType sort, long townId) {
        return index.page(sort, List.of(townId), NO_FILTER, null, Integer.MAX_VALUE - 1);
    }

    private static Set<Long> placeIds(List<PlaceListEntry> entries) {
        return entries.stream().map(PlaceListEntry::placeId).collect(Collectors.toSet());
    }

    /**
     * <b>정렬 다섯이 같은 동네에서 내놓는 원소 집합은 전부 같다.</b> 다른 것은 순서뿐이다.
     * 길이와 placeId 집합을 함께 묻는 것은 중복 원소가 낀 경우까지 잡기 위해서다.
     */
    @Test
    void 정렬_다섯은_같은_원소_집합을_담는다() {
        PlaceListIndex index = fixture();

        for (long townId : new long[]{TOWN_A, TOWN_B}) {
            List<PlaceListEntry> baseline = all(index, PlaceSortType.LATEST, townId);

            for (PlaceSortType sort : PlaceSortType.values()) {
                if (sort == PlaceSortType.DISTANCE) {
                    continue; // 사전 정렬 축이 아니다
                }
                List<PlaceListEntry> sorted = all(index, sort, townId);

                assertThat(sorted)
                        .as("town=%d, sort=%s 의 원소 수", townId, sort)
                        .hasSameSizeAs(baseline);
                assertThat(placeIds(sorted))
                        .as("town=%d, sort=%s 의 원소 집합", townId, sort)
                        .isEqualTo(placeIds(baseline));
            }
        }
    }

    /**
     * <b>거리순 후보는 좌표가 있는 전 원소다.</b> 후보를 정렬 배열에서 긁어 오므로, 위 불변식이
     * 깨지면 여기가 조용히 좁아진다 — 두 테스트가 같은 것을 앞뒤로 지킨다.
     */
    @Test
    void 거리순_후보는_좌표_있는_전_원소다() {
        PlaceListIndex index = fixture();

        List<PlaceListEntry> candidates =
                index.distanceCandidates(List.of(TOWN_A, TOWN_B), NO_FILTER);

        assertThat(placeIds(candidates)).containsExactlyInAnyOrder(2L, 4L, 5L);
    }
}
