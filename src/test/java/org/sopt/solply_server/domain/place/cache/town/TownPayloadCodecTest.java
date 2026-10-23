package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.support.TestMeters;

/**
 * 공유 사본의 JSON 형식. 복원한 객체가 원본과 <b>필드 하나까지</b> 같고, 믿을 수 없는 내용은
 * 객체로 만들지 않는다.
 */
class TownPayloadCodecTest {

    private static final long TOWN = 7L;
    private static final TownCacheKey KEY = new TownCacheKey(TOWN, 3L);

    private final TownPayloadCodec codec = new TownPayloadCodec();

    @Test
    void 전체_필드가_왕복하고_인기순이_같다() {
        TownPlaces original = sample();

        TownPlaces restored = codec.decode(KEY, codec.encode(original));

        assertThat(restored.townId()).isEqualTo(TOWN);
        assertThat(restored.version()).isEqualTo(3L);
        assertThat(restored.members()).containsExactlyInAnyOrder(original.members());
        for (PlaceEntry entry : original.members()) {
            assertThat(restored.display(entry.placeId())).isEqualTo(original.display(entry.placeId()));
        }
        assertThat(restored.hasOrder(PlaceSortType.POPULAR)).as("정렬 배열은 싣지 않는다").isFalse();
        assertThat(restored.order(PlaceSortType.POPULAR, TestMeters.noop()))
                .containsExactly(original.order(PlaceSortType.POPULAR, TestMeters.noop()));
        assertThat(restored.estimatedBytes()).isEqualTo(original.estimatedBytes());
    }

    /** 벤치 payload와 같은 모양 — 스키마 1, 이름을 줄이지 않은 필드, 정렬 배열 없음. */
    @Test
    void 형식은_측정_payload와_같은_필드를_쓴다() throws Exception {
        JsonNode root = new ObjectMapper().readTree(codec.encode(sample()));

        assertThat(root.get("schema").asInt()).isEqualTo(1);
        assertThat(root.get("townId").asLong()).isEqualTo(TOWN);
        assertThat(root.get("version").asLong()).isEqualTo(3L);
        List<String> fields = new java.util.ArrayList<>();
        root.get("places").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("placeId", "tagBitmask", "popularScore",
                "createdAtEpochSecond", "bookmarkCount", "reviewCount", "ratingToInt",
                "latitude", "longitude", "name", "thumbnailFileKey", "mainTagId");
    }

    @Test
    void 스키마가_다르면_쓰지_않는다() {
        assertInvalid(json(2, TOWN, 3L, place(1L)));
    }

    @Test
    void 키와_동네나_번호가_다르면_쓰지_않는다() {
        assertInvalid(json(1, TOWN + 1, 3L, place(1L)));
        assertInvalid(json(1, TOWN, 4L, place(1L)));
    }

    @Test
    void 필드가_빠지면_쓰지_않는다() {
        assertInvalid(json(1, TOWN, 3L, place(1L).replace("\"popularScore\":2.5,", "")));
        // null을 허용하는 필드도 자리는 있어야 한다
        assertInvalid(json(1, TOWN, 3L, place(1L).replace(",\"mainTagId\":null", "")));
        assertInvalid("{\"schema\":1,\"townId\":7,\"version\":3}");
    }

    @Test
    void 원시_필드의_null은_쓰지_않는다() {
        assertInvalid(json(1, TOWN, 3L, place(1L).replace("\"reviewCount\":4", "\"reviewCount\":null")));
    }

    @Test
    void 같은_장소가_두_번_실리면_쓰지_않는다() {
        assertInvalid(json(1, TOWN, 3L, place(1L) + "," + place(1L)));
    }

    @Test
    void 빈_원소나_깨진_JSON은_쓰지_않는다() {
        assertInvalid(json(1, TOWN, 3L, "null"));
        assertInvalid("{not json");
        assertInvalid("");
    }

    @Test
    void 빈_동네도_왕복한다() {
        TownPlaces empty = TownPlaces.objectsOnly(TOWN, 3L, List.of(), Map.of());

        assertThat(codec.decode(KEY, codec.encode(empty)).placeCount()).isZero();
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> codec.decode(KEY, json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(TownPayloadCodec.InvalidPayloadException.class);
    }

    private static String json(int schema, long townId, long version, String places) {
        return "{\"schema\":" + schema + ",\"townId\":" + townId + ",\"version\":" + version
                + ",\"places\":[" + places + "]}";
    }

    private static String place(long placeId) {
        return "{\"placeId\":" + placeId + ",\"tagBitmask\":1,\"popularScore\":2.5,"
                + "\"createdAtEpochSecond\":1700000000,\"bookmarkCount\":3,\"reviewCount\":4,"
                + "\"ratingToInt\":450,\"latitude\":null,\"longitude\":null,\"name\":\"a\","
                + "\"thumbnailFileKey\":null,\"mainTagId\":null}";
    }

    /** 표시값의 null·좌표 null·같은 점수(동률)·긴 한글 이름을 섞는다. */
    private static TownPlaces sample() {
        List<PlaceEntry> entries = List.of(
                new PlaceEntry(101L, TOWN, 5L, 9.5, 1_700_000_000L, 12L, 3L, 450, 37.5, 127.01),
                new PlaceEntry(102L, TOWN, 0L, 9.5, 1_700_000_100L, 0L, 0L, 0, null, null),
                new PlaceEntry(103L, TOWN, Long.MIN_VALUE, -1.25, 1_600_000_000L, 1L, 99L, 500,
                        -33.9, 151.2));
        Map<Long, PlaceView> displays = Map.of(
                101L, new PlaceView(101L, "성수 조용한 카페 ☕ \"따옴표\"", "place/101/a.jpg", 3L),
                102L, new PlaceView(102L, "이름", null, null),
                103L, new PlaceView(103L, "", "", 128L));
        return TownPlaces.objectsOnly(TOWN, 3L, entries, displays);
    }
}
