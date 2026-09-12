package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.publication.PublishedSnapshot;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayload;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayloadCodec;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;

/**
 * 발행물을 <b>내려받아 설치하는 쪽</b>의 계약. 설치는 이 인스턴스의 힙이 바뀌는 <b>유일한</b>
 * 경로라, 여기서 거르지 못한 것은 그대로 목록 응답이 된다.
 *
 * <p>이 파일이 잇는 것은 옛 {@code SnapshotLoaderPartialUpdateTest}의 뒷절반이다 — 그때는
 * "전량 재빌드와 부분 패치가 서로를 덮지 않는가"였고, 지금은 <b>"낡은 payload가 새것을 덮지
 * 않는가"</b>로 자리가 옮겨졌다. 옛 파일의 앞절반(정렬 배열이 어떻게 갈리는가)은
 * {@code SortedPlacesTest}가 그대로 들고 있고, 어드민 훅 쪽은 {@code SnapshotRefresherTest}가
 * 이어받았다.
 *
 * <p>코덱은 <b>진짜를 쓴다.</b> 이 파일의 절반이 "깨진 payload를 거절하는가"인데, 목으로 두면
 * 검증 자체가 사라져 통과만 남는다.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotInstallerTest {

    private static final long PUBLICATION_ID = 42L;
    private static final long CURSOR_VERSION = 42L;

    @Mock private SnapshotPublicationRepository publicationRepository;

    /**
     * 코덱·상자·홀더·락은 진짜다 — 무엇이 걸러지고 무엇이 설치됐는지가 이 파일의 단언이다.
     *
     * <p><b>목이나 스파이로 두지 않는다.</b> 픽스처가 체크섬을 만들 때 코덱을 부르는데, 스파이면
     * 그 호출이 다른 목의 스터빙 한가운데에 끼어 {@code UnfinishedStubbingException}이 난다.
     * 그래서 설치자도 {@code @InjectMocks} 대신 손으로 엮는다.
     */
    private final SnapshotPayloadCodec codec = new SnapshotPayloadCodec();
    private final SnapshotBox snapshotBox = new SnapshotBox();
    private final PlaceViewHolder placeViewHolder = new PlaceViewHolder();
    private final TagViewHolder tagViewHolder = new TagViewHolder();
    private final CacheWriteLock writeLock = new CacheWriteLock();

    private SnapshotInstaller installer;

    @BeforeEach
    void wire() {
        installer = new SnapshotInstaller(publicationRepository, codec, snapshotBox,
                placeViewHolder, tagViewHolder, writeLock);
    }

    // === 무부하 폴 (설계 §11-25) ===

    /**
     * <b>포인터가 그대로면 payload를 읽지 않는다.</b> payload는 전량이라 폴마다 내려받으면 5초
     * 간격으로 스냅샷 한 벌이 네트워크와 힙을 오간다 — 폴이 싼 이유가 이 조건 하나다.
     */
    @Test
    void 포인터가_그대로면_payload를_읽지_않는다() {
        givenPublished(validPayload());
        assertThat(installer.installIfChanged()).isTrue();

        boolean second = installer.installIfChanged();

        assertThat(second).isFalse();
        // 내려받기는 첫 설치의 한 번뿐이다
        verify(publicationRepository, org.mockito.Mockito.times(1)).download();
    }

    /** 아직 아무도 발행하지 않았으면 설치할 것이 없다 — 기동 부트스트랩이 맡는 자리다 */
    @Test
    void 발행물이_없으면_아무것도_설치하지_않는다() {
        given(publicationRepository.readCurrentPublicationId()).willReturn(null);

        assertThat(installer.installIfChanged()).isFalse();
        verify(publicationRepository, never()).download();
    }

    // === 깨진 payload를 거절한다 (설계 §11-21·22) ===

    /**
     * <b>체크섬이 다르면 설치하지 않는다.</b> 압축된 바이트가 한 비트라도 갈리면 디코딩이 엉뚱한
     * 값을 만들거나 조용히 성공한다 — 후자가 더 나쁘다.
     */
    @Test
    void 체크섬이_다르면_설치하지_않는다() {
        byte[] payload = validPayload();
        given(publicationRepository.readCurrentPublicationId()).willReturn(PUBLICATION_ID);
        given(publicationRepository.download()).willReturn(Optional.of(new PublishedSnapshot(
                PUBLICATION_ID, CURSOR_VERSION, SnapshotPayloadCodec.FORMAT_VERSION,
                1, payload.length, "다른체크섬", payload)));

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("깨졌다");
        assertNothingInstalled();
    }

    /**
     * <b>엔트리 수가 행이 말한 값과 다르면 설치하지 않는다.</b> 체크섬은 바이트가 온전한지만
     * 말하고, 이 대조는 <b>그 바이트가 정말 그 발행의 것인지</b>를 한 겹 더 본다.
     */
    @Test
    void 엔트리_수가_행과_다르면_설치하지_않는다() {
        givenDownload(validPayload(), SnapshotPayloadCodec.FORMAT_VERSION, 99);

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("엔트리 수");
        assertNothingInstalled();
    }

    /**
     * <b>모르는 형식은 재해석하지 않는다.</b> 새 형식을 옛 코드가 "아는 필드만 읽는" 식으로
     * 받아들이면 빠진 필드가 기본값이 되어 <b>정렬이 조용히 틀린다.</b> 읽지 않는 것이 맞는 답이다.
     */
    @Test
    void 지원하지_않는_형식은_설치하지_않는다() {
        givenDownload(validPayload(), SnapshotPayloadCodec.FORMAT_VERSION + 1, 1);

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("지원하지 않는");
        assertNothingInstalled();
    }

    /** 같은 장소가 두 번 실리면 정렬 배열에 같은 원소가 둘 생겨 페이지가 겹친다 */
    @Test
    void 같은_장소가_두_번_실린_payload는_거절한다() {
        byte[] payload = gzip("""
                {"formatVersion":1,
                 "entries":[%s,%s],
                 "places":[],"tags":[]}
                """.formatted(entryJson(7L), entryJson(7L)));
        givenDownload(payload, SnapshotPayloadCodec.FORMAT_VERSION, 2);

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("같은 장소가 두 번");
        assertNothingInstalled();
    }

    /**
     * <b>유한하지 않은 정렬 키는 거절한다.</b> {@code NaN}은 비교에서 어느 쪽으로도 크지 않아
     * 정렬 결과가 입력 순서에 따라 달라진다 — 노드마다 다른 순서가 나오는 자리다.
     */
    @Test
    void 유한하지_않은_정렬_키는_거절한다() {
        byte[] payload = gzip("""
                {"formatVersion":1,
                 "entries":[{"placeId":7,"townId":1,"tagBitmask":0,"popularScore":"NaN",
                   "createdAtEpochSecond":1,"bookmarkCount":0,"reviewCount":0,"ratingToInt":0,
                   "latitude":null,"longitude":null}],
                 "places":[],"tags":[]}
                """);
        givenDownload(payload, SnapshotPayloadCodec.FORMAT_VERSION, 1);

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("유한하지 않다");
        assertNothingInstalled();
    }

    /** 필수 필드가 빠지면 그 자리가 기본값으로 채워져 조용히 틀린 스냅샷이 된다 */
    @Test
    void 필수_필드가_빠진_payload는_거절한다() {
        byte[] payload = gzip("""
                {"formatVersion":1,
                 "entries":[{"placeId":7,"townId":1}],
                 "places":[],"tags":[]}
                """);
        givenDownload(payload, SnapshotPayloadCodec.FORMAT_VERSION, 1);

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(Exception.class);
        assertNothingInstalled();
    }

    /** primitive 자리의 {@code null}도 같다 — 0으로 읽히면 그 장소가 목록 맨 뒤로 간다 */
    @Test
    void primitive_자리의_null은_거절한다() {
        byte[] payload = gzip("""
                {"formatVersion":1,
                 "entries":[{"placeId":7,"townId":1,"tagBitmask":0,"popularScore":null,
                   "createdAtEpochSecond":1,"bookmarkCount":0,"reviewCount":0,"ratingToInt":0,
                   "latitude":null,"longitude":null}],
                 "places":[],"tags":[]}
                """);
        givenDownload(payload, SnapshotPayloadCodec.FORMAT_VERSION, 1);

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(Exception.class);
        assertNothingInstalled();
    }

    // === 실패해도 직전 회차를 지킨다 (설계 §11-23) ===

    /**
     * <b>내려받기가 죽어도 직전 스냅샷이 그대로 서빙된다.</b> 설치에 실패한 인스턴스가 빈 목록을
     * 내는 것보다 조금 낡은 목록을 내는 편이 낫다. 다음 폴이 다시 시도한다.
     */
    @Test
    void 내려받기가_실패해도_직전_스냅샷이_그대로다() {
        givenPublished(validPayload());
        installer.installIfChanged();
        long installedBefore = installer.installedPublicationId();

        given(publicationRepository.readCurrentPublicationId()).willReturn(PUBLICATION_ID + 1);
        given(publicationRepository.download())
                .willThrow(new IllegalStateException("내려받기 실패"));

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(IllegalStateException.class);
        assertThat(installer.installedPublicationId()).isEqualTo(installedBefore);
        assertThat(snapshotBox.current()).isNotNull();
    }

    /**
     * <b>설치가 실패해도 다음 설치가 락을 잡을 수 있다.</b> 예외 경로에서 락을 놓지 않으면 그
     * 인스턴스의 목록 갱신이 통째로 멎고, 증상은 "낡은 채로 멈춤"이라 오류 로그 없이 지나간다.
     */
    @Test
    void 설치가_실패해도_다음_설치가_락을_잡을_수_있다() {
        given(publicationRepository.readCurrentPublicationId()).willReturn(PUBLICATION_ID);
        given(publicationRepository.download())
                .willThrow(new IllegalStateException("첫 번째 실패"))
                .willReturn(Optional.of(published(validPayload(), PUBLICATION_ID)));

        assertThatThrownBy(() -> installer.installIfChanged())
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> installer.installIfChanged()).doesNotThrowAnyException();
        assertThat(installer.installedPublicationId()).isEqualTo(PUBLICATION_ID);
    }

    // === 늦게 도착한 옛 payload (설계 §11-24) ===

    /**
     * <b>이미 설치한 것보다 낡은 발행물은 덮지 못한다.</b> 내려받기는 락 <em>밖</em>에서 도는데,
     * 그 사이 어드민 훅이 더 새 발행물을 설치할 수 있다. 락 안에서 id를 다시 보지 않으면 늦게
     * 도착한 옛 payload가 방금 반영한 이름·태그를 되돌린다 — 옛 구조에서 "전량이 패치를 덮는"
     * 문제였던 자리가 그대로 여기로 옮겨 왔다.
     */
    @Test
    void 낡은_발행물은_이미_설치한_것을_덮지_않는다() {
        givenPublished(validPayload());
        installer.installIfChanged();
        assertThat(installer.installedPublicationId()).isEqualTo(PUBLICATION_ID);
        String installedName = placeViewHolder.get(7L).name();

        // 락 밖에서 내려받는 사이에 더 새 것이 설치됐다 — 늦게 도착한 payload가 이것이다
        given(publicationRepository.download())
                .willReturn(Optional.of(published(olderPayload(), PUBLICATION_ID - 1)));

        assertThat(installer.installLatest()).isFalse();
        assertThat(installer.installedPublicationId()).isEqualTo(PUBLICATION_ID);
        assertThat(placeViewHolder.get(7L).name()).isEqualTo(installedName);
    }

    // === 설치가 실제로 갈아 끼운다 ===

    /** 정상 경로 — 정렬 배열·표시값·태그가 한꺼번에 갈리고 커서 회차가 발행물의 것이 된다 */
    @Test
    void 설치하면_정렬과_표시값과_태그가_함께_갈린다() {
        givenPublished(validPayload());

        assertThat(installer.installIfChanged()).isTrue();

        assertThat(snapshotBox.current().version()).isEqualTo(CURSOR_VERSION);
        assertThat(snapshotBox.current().sortedPlaces().placeCount()).isEqualTo(1);
        assertThat(placeViewHolder.get(7L).name()).isEqualTo("설치된 이름");
        assertThat(tagViewHolder.get(3L).name()).isEqualTo("설치된 태그");
    }

    // === 픽스처 ===

    private void givenPublished(byte[] payload) {
        given(publicationRepository.readCurrentPublicationId()).willReturn(PUBLICATION_ID);
        given(publicationRepository.download())
                .willReturn(Optional.of(published(payload, PUBLICATION_ID)));
    }

    private void givenDownload(byte[] payload, int formatVersion, int entryCount) {
        given(publicationRepository.readCurrentPublicationId()).willReturn(PUBLICATION_ID);
        given(publicationRepository.download()).willReturn(Optional.of(new PublishedSnapshot(
                PUBLICATION_ID, CURSOR_VERSION, formatVersion, entryCount,
                payload.length, codec.checksum(payload), payload)));
    }

    private PublishedSnapshot published(byte[] payload, long publicationId) {
        return new PublishedSnapshot(publicationId, publicationId,
                SnapshotPayloadCodec.FORMAT_VERSION, 1, payload.length,
                codec.checksum(payload), payload);
    }

    private byte[] validPayload() {
        return codec.encode(new SnapshotPayload(
                SnapshotPayloadCodec.FORMAT_VERSION,
                List.of(new SnapshotPayload.Entry(
                        7L, 1L, 0b1L, 5.0, 1_700_000_000L, 3L, 2L, 450, 37.5, 127.0)),
                List.of(new SnapshotPayload.Place(7L, "설치된 이름", "7.jpg", 3L)),
                List.of(new SnapshotPayload.Tag(3L, "설치된 태그", true))));
    }

    private byte[] olderPayload() {
        return codec.encode(new SnapshotPayload(
                SnapshotPayloadCodec.FORMAT_VERSION,
                List.of(new SnapshotPayload.Entry(
                        7L, 1L, 0b1L, 5.0, 1_700_000_000L, 3L, 2L, 450, 37.5, 127.0)),
                List.of(new SnapshotPayload.Place(7L, "되돌아가면 안 되는 옛 이름", "old.jpg", 3L)),
                List.of(new SnapshotPayload.Tag(3L, "옛 태그", true))));
    }

    private static String entryJson(long placeId) {
        return """
                {"placeId":%d,"townId":1,"tagBitmask":0,"popularScore":1.0,
                 "createdAtEpochSecond":1,"bookmarkCount":0,"reviewCount":0,"ratingToInt":0,
                 "latitude":null,"longitude":null}
                """.formatted(placeId);
    }

    private static byte[] gzip(String json) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 거절된 payload는 <b>설치 id도 힙도</b> 건드리지 않았어야 한다 */
    private void assertNothingInstalled() {
        assertThat(installer.installedPublicationId()).isEqualTo(-1L);
        assertThat(snapshotBox.current()).isNull();
        assertThat(placeViewHolder.get(7L)).isNull();
    }
}
