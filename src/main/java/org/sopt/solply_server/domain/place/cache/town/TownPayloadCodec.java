package org.sopt.solply_server.domain.place.cache.town;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;

/**
 * 공유 저장소에 싣는 동네 한 번호의 <b>무압축 JSON</b> 표현. 형식은 2026-09-24 Redis 적재 측정의
 * payload와 같다(필드 이름을 줄이지 않고, 필터·정렬·표시값 전부를 싣는다).
 *
 * <p><b>읽기는 믿지 않는다.</b> 스키마 번호, 동네·번호가 키와 같은지, 모든 필드가 실려 있는지,
 * 같은 장소가 두 번 실리지 않았는지를 본 뒤에만 새 {@link TownPlaces}를 만든다. 어긋나면
 * {@link InvalidPayloadException} — 호출부는 이것을 "없음"이 아니라 "확인 불가"로 다룬다.
 *
 * <p>정렬 배열은 싣지 않는다. 복원한 객체도 적재한 객체와 같이 요청받은 축만 그 자리에서 만든다.
 */
public final class TownPayloadCodec {

    public static final int SCHEMA = 1;

    public record TownPayload(int schema, long townId, long version, List<PlacePayload> places) {
    }

    /** 원시 타입은 null로 채워지지 않고, 빠진 필드는 기본값 대신 실패한다(아래 매퍼 설정). */
    public record PlacePayload(long placeId, long tagBitmask, double popularScore,
            long createdAtEpochSecond, long bookmarkCount, long reviewCount, int ratingToInt,
            Double latitude, Double longitude, String name, String thumbnailFileKey,
            Long mainTagId) {
    }

    public static final class InvalidPayloadException extends RuntimeException {
        InvalidPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public byte[] encode(TownPlaces places) {
        PlaceEntry[] members = places.members();
        List<PlacePayload> list = new ArrayList<>(members.length);
        for (PlaceEntry e : members) {
            PlaceView v = places.display(e.placeId());
            list.add(new PlacePayload(e.placeId(), e.tagBitmask(), e.popularScore(),
                    e.createdAtEpochSecond(), e.bookmarkCount(), e.reviewCount(), e.ratingToInt(),
                    e.latitude(), e.longitude(),
                    v == null ? null : v.name(),
                    v == null ? null : v.thumbnailFileKey(),
                    v == null ? null : v.mainTagId()));
        }
        try {
            return mapper.writeValueAsBytes(
                    new TownPayload(SCHEMA, places.townId(), places.version(), list));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("동네 payload 직렬화 실패 - townId=" + places.townId(), e);
        }
    }

    /**
     * @param key 이 바이트를 꺼낸 키. 내용의 동네·번호가 이 키와 다르면 쓰지 않는다
     */
    public TownPlaces decode(TownCacheKey key, byte[] bytes) {
        TownPayload payload;
        try {
            payload = mapper.readValue(bytes, TownPayload.class);
        } catch (IOException e) {
            throw invalid(key, "JSON 해석 실패", e);
        }
        if (payload == null || payload.schema() != SCHEMA) {
            throw invalid(key, "스키마 불일치 - " + (payload == null ? null : payload.schema()),
                    null);
        }
        if (payload.townId() != key.townId() || payload.version() != key.version()) {
            throw invalid(key, "키와 내용이 다르다 - payload=" + payload.townId() + "@"
                    + payload.version(), null);
        }
        if (payload.places() == null) {
            throw invalid(key, "places가 없다", null);
        }
        int n = payload.places().size();
        List<PlaceEntry> entries = new ArrayList<>(n);
        Map<Long, PlaceView> displays = new HashMap<>(n * 2);
        for (PlacePayload p : payload.places()) {
            if (p == null) {
                throw invalid(key, "빈 장소 원소", null);
            }
            if (displays.put(p.placeId(),
                    new PlaceView(p.placeId(), p.name(), p.thumbnailFileKey(), p.mainTagId()))
                    != null) {
                throw invalid(key, "같은 장소가 두 번 실렸다 - placeId=" + p.placeId(), null);
            }
            entries.add(new PlaceEntry(p.placeId(), key.townId(), p.tagBitmask(),
                    p.popularScore(), p.createdAtEpochSecond(), p.bookmarkCount(),
                    p.reviewCount(), p.ratingToInt(), p.latitude(), p.longitude()));
        }
        return TownPlaces.objectsOnly(key.townId(), key.version(), entries, displays);
    }

    private static InvalidPayloadException invalid(TownCacheKey key, String reason,
            Throwable cause) {
        return new InvalidPayloadException(
                "동네 payload가 올바르지 않다 - " + key + ": " + reason, cause);
    }
}
