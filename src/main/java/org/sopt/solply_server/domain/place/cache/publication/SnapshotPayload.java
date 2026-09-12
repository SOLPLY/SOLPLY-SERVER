package org.sopt.solply_server.domain.place.cache.publication;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 발행되는 스냅샷 한 벌의 <b>내용</b>. Java 직렬화를 쓰지 않는 것이 이 타입의 존재 이유다 —
 * 필드 이름이 형식이라 새 배포가 필드를 늘려도 옛 배포가 읽는다.
 *
 * <p><b>커서 회차를 싣지 않는다.</b> 구조 발행의 회차는 INSERT가 정하는 id라 payload를 만드는
 * 시점에 알 수 없고, 두 곳에 두면 어느 쪽이 정본인지 물어야 한다. 회차는
 * {@code place_list_publications.cursor_version} 컬럼이 정본이고, payload는 언제나 자기 행과
 * 함께 읽히므로 부족하지 않다.
 *
 * <p><b>모든 필드가 필수다</b>({@code required = true}). 빠진 필드를 {@code 0}·{@code false}로
 * 채우면 같은 회차를 달고 다른 내용이 복원된다 — 이 형식에서 가장 위험한 실패다. null이 허용되는
 * 자리는 넷뿐이고({@code latitude}·{@code longitude}·{@code mainTagId}·{@code thumbnailFileKey})
 * 그 넷도 <b>키는 있어야 하고 값만 null</b>이다.
 *
 * <p><b>형식을 바꿀 때의 규칙.</b> 필드를 늘리는 것은 같은 {@code formatVersion}에서 허용되지만,
 * <b>그 필드가 순서·필터·커서 해석에 끼어들지 않을 때만</b>이다. 비교자·단위·소속 판정을 건드리는
 * 변경은 옛 배포가 같은 회차 번호로 다른 순서를 내게 하므로, 필드를 늘리는 모양이더라도
 * {@code SnapshotPayloadCodec#FORMAT_VERSION}을 올리고 전환 계획을 함께 세울 것. 필드를 지우거나
 * 뜻·단위를 바꾸는 것은 언제나 형식을 올린다.
 */
public record SnapshotPayload(
        @JsonProperty(value = "formatVersion", required = true) int formatVersion,
        @JsonProperty(value = "entries", required = true) List<Entry> entries,
        @JsonProperty(value = "places", required = true) List<Place> places,
        @JsonProperty(value = "tags", required = true) List<Tag> tags) {

    /**
     * {@code PlaceEntry} 열 개가 전부 있어야 복원한 배열이 원본과 같은 순서를 낸다.
     *
     * <p>단위를 못 박는다 — 값이 같아야 순서가 같다.
     * <ul>
     *   <li>{@code createdAtEpochSecond} — {@code createdAt}을 UTC로 간주한 epoch 초.
     *       커서가 싣는 값과 같은 식이다.</li>
     *   <li>{@code ratingToInt} — 평점 × 100의 정수. 스케일 2를 응답에서 되씌운다.</li>
     *   <li>{@code popularScore} — 정렬 비교가 {@code Double.compare}이므로 재직렬화가 값을
     *       바꾸면 순서가 갈린다. {@code NaN}·무한대는 디코딩에서 거절한다.</li>
     * </ul>
     */
    public record Entry(
            @JsonProperty(value = "placeId", required = true) long placeId,
            @JsonProperty(value = "townId", required = true) long townId,
            @JsonProperty(value = "tagBitmask", required = true) long tagBitmask,
            @JsonProperty(value = "popularScore", required = true) double popularScore,
            @JsonProperty(value = "createdAtEpochSecond", required = true) long createdAtEpochSecond,
            @JsonProperty(value = "bookmarkCount", required = true) long bookmarkCount,
            @JsonProperty(value = "reviewCount", required = true) long reviewCount,
            @JsonProperty(value = "ratingToInt", required = true) int ratingToInt,
            @JsonProperty(value = "latitude", required = true) Double latitude,
            @JsonProperty(value = "longitude", required = true) Double longitude) {
    }

    /** 표시값 홀더 한 항목. {@code name}은 null일 수 없고 나머지 둘은 값만 null일 수 있다. */
    public record Place(
            @JsonProperty(value = "placeId", required = true) long placeId,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "thumbnailFileKey", required = true) String thumbnailFileKey,
            @JsonProperty(value = "mainTagId", required = true) Long mainTagId) {
    }

    /** 태그 홀더 한 항목. <b>비활성 태그도 실린다</b> — 없으면 대표 태그 판정이 갈린다. */
    public record Tag(
            @JsonProperty(value = "tagId", required = true) long tagId,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "active", required = true) boolean active) {
    }
}
