package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.publication.ProcessedMark;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.PublishedSnapshot;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayload;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayloadCodec;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 발행물 payload의 <b>크기와 왕복 시간을 눈으로 보는</b> 자리.
 *
 * <p>계획서가 배포 전에 확인하라고 적은 값이 둘이다 — 저장되는 바이트 수(서버
 * {@code max_allowed_packet}와 대조할 값)와, 그것을 짓고 내려받고 푸는 데 걸리는 시간.
 * 그 둘을 <b>고정된 합성 픽스처</b> 위에서 한 번 재어 기록한다.
 *
 * <p><b>이 수치는 성능 주장이 아니다.</b> 개발 노트북의 Testcontainers MySQL 위에서, 같은 JVM
 * 안에서, 픽스처 하나로 잰 값이다. 운영 데이터의 이름·썸네일 키 길이 분포가 다르면 압축비가
 * 그대로 달라지고, 운영 서버의 디스크·네트워크도 여기와 다르다. <b>운영 규모를 예측하는 데
 * 쓰지 말 것</b> — 배포 뒤 발행 로그의 {@code bytes=}를 직접 보는 것이 정본이다.
 *
 * <p>단언은 시간이 아니라 <b>정합성</b>에만 건다. 시간에 임계값을 걸면 기계가 바쁠 때마다 빨개지는
 * 테스트가 되고, 그런 테스트는 무엇도 지키지 못한 채 신뢰만 깎는다.
 */
@SpringBootTest
class SnapshotPayloadSizeIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void payloadSizeProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    /** 반복 횟수 — 첫 회의 JIT·클래스 로딩을 걷어내고 최소값을 함께 보기 위한 최소한이다 */
    private static final int ROUNDS = 5;
    private static final int TAG_COUNT = 40;

    @Autowired private SnapshotPayloadCodec codec;
    @Autowired private SnapshotPublicationRepository publicationRepository;
    @Autowired private SnapshotPublicationService publicationService;

    /**
     * 장소 1,000개와 10,000개에서 각각 재고, 그 자리에서 <b>되읽은 것이 넣은 것과 같은지</b>까지
     * 확인한다. 크기만 재고 정합성을 안 보면 "압축은 잘 됐는데 못 읽는" 상태를 지나친다.
     */
    @Test
    void 고정_픽스처의_payload_크기와_왕복_시간을_남긴다() {
        for (int entryCount : new int[] {1_000, 10_000}) {
            measureAndReport(entryCount);
        }
    }

    private void measureAndReport(int entryCount) {
        SnapshotPayload fixture = fixedFixture(entryCount);
        long rawJsonBytes = rawJsonBytes(fixture);

        long encodeBestNanos = Long.MAX_VALUE;
        long decodeBestNanos = Long.MAX_VALUE;
        long loadBestNanos = Long.MAX_VALUE;
        byte[] encoded = null;

        for (int round = 0; round < ROUNDS; round++) {
            long t0 = System.nanoTime();
            encoded = codec.encode(fixture);
            encodeBestNanos = Math.min(encodeBestNanos, System.nanoTime() - t0);

            long publicationId = publish(encoded, entryCount);

            long t1 = System.nanoTime();
            PublishedSnapshot published = publicationRepository.download().orElseThrow();
            loadBestNanos = Math.min(loadBestNanos, System.nanoTime() - t1);

            long t2 = System.nanoTime();
            SnapshotPayload decoded = codec.decode(
                    published.formatVersion(), published.payload(), published.entryCount());
            decodeBestNanos = Math.min(decodeBestNanos, System.nanoTime() - t2);

            assertThat(published.publicationId()).isEqualTo(publicationId);
            assertThat(decoded.entries()).hasSize(entryCount);
            assertThat(decoded.entries().get(0)).isEqualTo(fixture.entries().get(0));
            assertThat(decoded.entries().get(entryCount - 1))
                    .isEqualTo(fixture.entries().get(entryCount - 1));
            assertThat(decoded.tags()).hasSize(TAG_COUNT);
        }

        // 리포트에 그대로 옮길 한 줄. 단언이 아니라 기록이다.
        System.out.printf(
                "### PAYLOAD entries=%d tags=%d rawJsonBytes=%d gzipBytes=%d ratio=%.2f%%"
                        + " encodeBestMs=%.1f loadBestMs=%.1f decodeBestMs=%.1f rounds=%d%n",
                entryCount, TAG_COUNT, rawJsonBytes, encoded.length,
                100.0 * encoded.length / rawJsonBytes,
                encodeBestNanos / 1_000_000.0, loadBestNanos / 1_000_000.0,
                decodeBestNanos / 1_000_000.0, ROUNDS);

        assertThat(encoded.length)
                .as("압축된 payload가 원본 JSON보다 작다")
                .isLessThan((int) rawJsonBytes);
    }

    private long publish(byte[] encoded, int entryCount) {
        PublicationCandidate candidate = new PublicationCandidate(
                null, SnapshotPayloadCodec.FORMAT_VERSION, entryCount,
                codec.checksum(encoded), encoded);
        return publicationService.publish(
                candidate, publicationRepository.readCurrentPublicationId(), ProcessedMark.none());
    }

    /** 압축 전 크기 — 압축비를 말하려면 분모가 있어야 한다 */
    private long rawJsonBytes(SnapshotPayload payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(payload).length;
        } catch (Exception e) {
            throw new IllegalStateException("원본 JSON 크기를 재지 못했다", e);
        }
    }

    /**
     * <b>고정 픽스처</b> — 난수를 쓰지 않는다. 같은 입력이 같은 바이트 수를 내야 이 수치를 다음에
     * 다시 잴 때 비교할 수 있다.
     *
     * <p>값을 전부 같게 두면 gzip이 비현실적으로 잘 접으므로, 이름·키·점수를 <b>인덱스로 흔들어</b>
     * 현실에 가까운 다양성을 준다. 그래도 실제 상호명 분포와 같다는 뜻은 아니다.
     */
    private static SnapshotPayload fixedFixture(int entryCount) {
        List<SnapshotPayload.Entry> entries = new ArrayList<>(entryCount);
        List<SnapshotPayload.Place> places = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            long placeId = 1_000L + i;
            entries.add(new SnapshotPayload.Entry(
                    placeId,
                    1L + (i % 41),                       // 동네 41개
                    1L << (i % 40),                      // 태그 비트 하나
                    100.0 - (i % 1000) * 0.137,          // 점수는 소수까지 흔든다
                    1_700_000_000L + i * 37L,
                    i % 500,
                    i % 120,
                    300 + (i % 200),
                    37.0 + (i % 1000) * 0.0004,
                    126.5 + (i % 1000) * 0.0005));
            places.add(new SnapshotPayload.Place(
                    placeId,
                    "테스트 장소 이름 %05d 한글과 English 섞임".formatted(i),
                    "place/%d/thumbnail-%05d.jpg".formatted(placeId, i),
                    1L + (i % TAG_COUNT)));
        }
        List<SnapshotPayload.Tag> tags = new ArrayList<>(TAG_COUNT);
        for (int i = 0; i < TAG_COUNT; i++) {
            tags.add(new SnapshotPayload.Tag(1L + i, "태그이름%02d".formatted(i), i % 7 != 0));
        }
        return new SnapshotPayload(SnapshotPayloadCodec.FORMAT_VERSION, entries, places, tags);
    }

    /** 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE place_list_publication_pointer SET publication_id = NULL"
                    + " WHERE id = 1");
            st.executeUpdate("DELETE FROM place_list_publications");
        }
    }
}
