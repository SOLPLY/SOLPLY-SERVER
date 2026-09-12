package org.sopt.solply_server.domain.place.cache.publication;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;

/**
 * 발행 payload의 바이트 표현 — <b>gzip으로 감싼 JSON</b>이다.
 *
 * <p>체크섬은 <b>저장되는(=압축된) 바이트</b>에 대해 잡는다. 검증하는 것이 곧 읽어 온 것이라야
 * "바이트가 깨졌다"와 "디코딩 버그"가 갈린다.
 *
 * <p><b>지원 목록 밖 형식은 예외다.</b> 모르는 형식을 읽어 보는 경로를 만들지 말 것 — 조용한
 * 오독이 낡은 스냅샷을 서빙하는 것보다 나쁘다. 기동에서는 이 예외가 컨텍스트를 접고, 폴에서는
 * 지금 스냅샷을 유지한 채 다음 폴이 다시 시도한다.
 */
@Component
public class SnapshotPayloadCodec {

    public static final int FORMAT_VERSION = 1;
    private static final Set<Integer> SUPPORTED = Set.of(FORMAT_VERSION);

    private final ObjectMapper mapper = JsonMapper.builder()
            // 새 배포가 늘린 필드를 옛 배포가 무시하고 읽게 한다 — 호환의 절반일 뿐이다.
            // 나머지 절반은 SnapshotPayload의 required=true와 아래 검증들이다
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // primitive 자리의 null을 조용한 0으로 만들지 않는다
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public byte[] encode(SnapshotPayload payload) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                mapper.writeValue(gzip, payload);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("목록 스냅샷 payload 인코딩 실패", e);
        }
    }

    /**
     * 바이트를 payload로 되돌리고 <b>구조까지 확인한다</b>. 형식·개수·중복·수치가 하나라도
     * 어긋나면 예외로 끝나고, 부른 쪽은 지금 상태를 그대로 둔다.
     */
    public SnapshotPayload decode(int formatVersion, byte[] encoded, int expectedEntryCount) {
        requireSupported(formatVersion);
        SnapshotPayload payload = read(encoded);
        if (payload.formatVersion() != formatVersion) {
            throw new IllegalStateException("발행물 행의 형식과 payload 안의 형식이 다르다 - row="
                    + formatVersion + ", payload=" + payload.formatVersion());
        }
        if (payload.entries().size() != expectedEntryCount) {
            throw new IllegalStateException("엔트리 수가 행이 말한 값과 다르다 - row="
                    + expectedEntryCount + ", payload=" + payload.entries().size());
        }
        requireSaneEntries(payload);
        return payload;
    }

    public String checksum(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    public void requireIntact(String expectedChecksum, byte[] encoded) {
        String actual = checksum(encoded);
        if (!actual.equals(expectedChecksum)) {
            throw new IllegalStateException(
                    "발행물 payload가 깨졌다 - expected=" + expectedChecksum + ", actual=" + actual);
        }
    }

    private SnapshotPayload read(byte[] encoded) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            return mapper.readValue(gzip, SnapshotPayload.class);
        } catch (IOException e) {
            throw new UncheckedIOException("목록 스냅샷 payload 디코딩 실패", e);
        }
    }

    /**
     * 형식은 맞는데 <b>내용이 배열을 못 짓는</b> 경우를 여기서 끊는다.
     *
     * <p>장소 id가 겹치면 같은 장소가 배열에 두 번 들어가 페이지가 항목을 겹쳐 낸다.
     * {@code popularScore}가 {@code NaN}이면 {@code Double.compare}가 전순서를 잃어
     * {@code Arrays.sort}가 계약 위반으로 던지거나 조용히 뒤섞인다.
     */
    private static void requireSaneEntries(SnapshotPayload payload) {
        Set<Long> seen = new HashSet<>(payload.entries().size() * 2);
        for (SnapshotPayload.Entry entry : payload.entries()) {
            if (!seen.add(entry.placeId())) {
                throw new IllegalStateException("payload에 같은 장소가 두 번 있다 - placeId="
                        + entry.placeId());
            }
            if (!Double.isFinite(entry.popularScore())) {
                throw new IllegalStateException("정렬 키가 유한하지 않다 - placeId="
                        + entry.placeId() + ", popularScore=" + entry.popularScore());
            }
        }
        Set<Long> tagIds = new HashSet<>(payload.tags().size() * 2);
        for (SnapshotPayload.Tag tag : payload.tags()) {
            if (!tagIds.add(tag.tagId())) {
                throw new IllegalStateException("payload에 같은 태그가 두 번 있다 - tagId="
                        + tag.tagId());
            }
        }
    }

    private static void requireSupported(int formatVersion) {
        if (!SUPPORTED.contains(formatVersion)) {
            throw new IllegalStateException("지원하지 않는 목록 스냅샷 형식이다 - format="
                    + formatVersion + ", supported=" + SUPPORTED);
        }
    }
}
