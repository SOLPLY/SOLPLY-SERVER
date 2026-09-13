package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;

/**
 * <b>스냅샷 한 벌을 갈아 끼우는 쪽</b>의 계약. 설치는 이 인스턴스의 힙이 바뀌는 <b>유일한</b>
 * 경로라, 여기서 거르지 못한 것은 그대로 목록 응답이 된다.
 *
 * <p>이 파일이 무는 것은 셋이다.
 * <ol>
 *   <li><b>번호와 데이터가 한 벌로 설치되는가</b> — 읽어 온 revision·cursorVersion이 그대로 실린다.</li>
 *   <li><b>낡은 리빌드가 새것을 덮지 않는가</b> — 단조 가드가 revision을 본다.</li>
 *   <li><b>표시값 즉시 패치와 리빌드가 서로를 되감지 않는가</b> — 이 클래스에서 가장 미묘한 부분이고,
 *       틀려도 예외가 나지 않아 화면에서만 보인다.</li>
 * </ol>
 *
 * <p>상자·홀더·락은 <b>진짜다</b>. 무엇이 설치됐고 무엇이 남았는지가 이 파일의 단언이라, 목으로
 * 두면 검증 자체가 사라져 통과만 남는다.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotInstallerTest {

    @Mock private SnapshotLoader loader;

    private final SnapshotBox snapshotBox = new SnapshotBox();
    private final PlaceViewHolder placeViewHolder = new PlaceViewHolder();
    private final TagViewHolder tagViewHolder = new TagViewHolder();
    private final CacheWriteLock writeLock = new CacheWriteLock();

    private SnapshotInstaller installer() {
        return new SnapshotInstaller(loader, snapshotBox, placeViewHolder, tagViewHolder,
                writeLock);
    }

    @Test
    void 읽어_온_번호_둘과_데이터를_한_벌로_설치한다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState()).willReturn(source(5L, 3L, place(1L, "밀크티집")));

        assertThat(installer.rebuildAndInstall()).isTrue();

        assertThat(installer.installedRevision()).isEqualTo(5L);
        assertThat(installer.installedCursorVersion()).isEqualTo(3L);
        assertThat(snapshotBox.current().revision()).isEqualTo(5L);
        assertThat(snapshotBox.current().cursorVersion()).isEqualTo(3L);
        assertThat(snapshotBox.current().sortedPlaces().placeCount()).isEqualTo(1);
        assertThat(placeViewHolder.get(1L).name()).isEqualTo("밀크티집");
    }

    /**
     * <b>단조 가드가 보는 것은 revision이다.</b> 겹쳐 돈 리빌드 중 늦게 도착한 낡은 쪽이 최신을
     * 되감으면, 그 뒤로 그 인스턴스는 "이미 최신이다"라고 믿으며 옛 데이터를 서빙한다.
     */
    @Test
    void 읽어_온_시점이_이미_설치한_것보다_낡으면_설치하지_않는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState())
                .willReturn(source(5L, 3L, place(1L, "새이름")))
                .willReturn(source(4L, 2L, place(1L, "옛이름")));

        installer.rebuildAndInstall();
        assertThat(installer.rebuildAndInstall()).isFalse();

        assertThat(installer.installedRevision()).isEqualTo(5L);
        assertThat(placeViewHolder.get(1L).name()).isEqualTo("새이름");
    }

    /**
     * <b>어드민이 "스크롤 유지"를 고른 회차의 모양이다 — cursorVersion은 그대로, revision만 오른다.</b>
     *
     * <p>여기서 가드가 cursorVersion을 봤다면 새 배열이 "이미 들고 있는 회차"로 오인돼 그 수정은
     * 영영 반영되지 않는다. 진행 중인 커서가 끊기지 않는 근거는 이 가드가 아니라 <b>커서가
     * cursorVersion을 싣는다</b>는 것이다.
     */
    @Test
    void 커서_회차가_같아도_시점이_새로우면_설치한다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState())
                .willReturn(source(5L, 3L, place(1L, "옛이름")))
                .willReturn(source(6L, 3L, place(1L, "새이름")));

        installer.rebuildAndInstall();
        assertThat(installer.rebuildAndInstall()).isTrue();

        assertThat(snapshotBox.current().cursorVersion()).isEqualTo(3L);
        assertThat(placeViewHolder.get(1L).name()).isEqualTo("새이름");
    }

    /** 읽다가 터지면 아무것도 바뀌지 않는다 — 지금 회차가 그대로 서빙된다. */
    @Test
    void 읽기가_실패하면_들고_있던_회차가_그대로_남는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState()).willReturn(source(5L, 3L, place(1L, "밀크티집")));
        installer.rebuildAndInstall();

        willThrow(new IllegalStateException("DB가 흔들린다")).given(loader).readSourceState();

        assertThatThrownBy(installer::rebuildAndInstall).isInstanceOf(IllegalStateException.class);
        assertThat(installer.installedRevision()).isEqualTo(5L);
        assertThat(snapshotBox.current().sortedPlaces().placeCount()).isEqualTo(1);
        assertThat(placeViewHolder.get(1L).name()).isEqualTo("밀크티집");
    }

    /**
     * <b>표시값 역행 — 이 파일의 핵심.</b> 리빌드가 revision 5의 원본을 읽고 있는 사이 어드민
     * 수정이 revision 6으로 커밋돼 이름을 메모리에 얹었다. 그 뒤 도착한 리빌드가 자기 맵을 통째로
     * 대입하면 방금 얹은 이름이 옛 이름으로 되돌아간다.
     */
    @Test
    void 리빌드보다_새로운_표시값_패치는_설치_뒤에도_살아남는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState())
                .willReturn(source(4L, 2L, place(1L, "처음이름")))
                .willReturn(source(5L, 2L, place(1L, "옛이름")));
        installer.rebuildAndInstall();

        // 어드민 수정이 revision 6으로 커밋되고 그 표시값을 메모리에 얹었다
        installer.patchPlaceViews(6L, List.of(1L), Map.of(1L, view(1L, "방금고친이름")));
        // revision 5를 읽고 있던 리빌드가 이제야 도착한다
        installer.rebuildAndInstall();

        assertThat(installer.installedRevision()).isEqualTo(5L);
        assertThat(placeViewHolder.get(1L).name()).isEqualTo("방금고친이름");
    }

    /**
     * <b>패치는 {@code installedRevision}을 올리지 않는다.</b> 올렸다면 그 번호까지의 리빌드가
     * 전부 "이미 설치했다"로 막히는데, 그 사이 번호에는 표시값이 아닌 변경 — 집계 회차의 정렬 키 —
     * 이 섞여 있다. 아래에서 revision 6의 리빌드가 실제로 설치돼야 그 변경이 살아난다.
     */
    @Test
    void 패치가_리빌드를_건너뛰게_만들지_않는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState())
                .willReturn(source(4L, 2L, place(1L, "처음이름")))
                .willReturn(source(6L, 3L, place(1L, "처음이름"), place(2L, "집계가_넣은_장소")));
        installer.rebuildAndInstall();

        installer.patchPlaceViews(6L, List.of(1L), Map.of(1L, view(1L, "방금고친이름")));
        assertThat(installer.rebuildAndInstall()).isTrue();

        assertThat(installer.installedRevision()).isEqualTo(6L);
        assertThat(snapshotBox.current().sortedPlaces().placeCount()).isEqualTo(2);
    }

    /**
     * 반대쪽 — 패치와 같은 시점(또는 그 뒤)을 읽은 리빌드는 그 패치를 이미 싣고 왔다. 보관하던
     * 패치를 버려야 그 뒤의 정상적인 변경이 옛 패치에 가려지지 않는다.
     */
    @Test
    void 패치를_이미_싣고_온_리빌드는_그_패치를_버린다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState())
                .willReturn(source(4L, 2L, place(1L, "처음이름")))
                .willReturn(source(6L, 2L, place(1L, "원본이_가진_이름")))
                .willReturn(source(7L, 2L, place(1L, "그다음이름")));
        installer.rebuildAndInstall();

        installer.patchPlaceViews(5L, List.of(1L), Map.of(1L, view(1L, "잠깐얹은이름")));
        installer.rebuildAndInstall();      // revision 6 — 패치(5)를 싣고 왔다
        assertThat(placeViewHolder.get(1L).name()).isEqualTo("원본이_가진_이름");

        installer.rebuildAndInstall();      // revision 7 — 버린 패치가 되살아나면 안 된다
        assertThat(placeViewHolder.get(1L).name()).isEqualTo("그다음이름");
    }

    /**
     * <b>이미 그 시점 이후를 통째로 읽어 실었다면 얹을 것이 없다.</b> 뒤늦게 도착한 패치가 리빌드가
     * 가져온 최신 값을 옛 값으로 되돌리는 것을 막는 자리다.
     */
    @Test
    void 이미_설치한_시점보다_낡은_패치는_얹지_않는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState()).willReturn(source(6L, 2L, place(1L, "리빌드가_실은_이름")));
        installer.rebuildAndInstall();

        installer.patchPlaceViews(5L, List.of(1L), Map.of(1L, view(1L, "낡은패치")));

        assertThat(placeViewHolder.get(1L).name()).isEqualTo("리빌드가_실은_이름");
    }

    /**
     * 삭제된 장소는 표시값이 없다. 홀더에서 지우는 것이 곧 목록에서 사라지는 것이다 — 조회 경로가
     * 표시값 없는 행을 건너뛴다.
     */
    @Test
    void 원본에서_사라진_장소는_표시값을_지운다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState()).willReturn(source(4L, 2L, place(1L, "곧지울집")));
        installer.rebuildAndInstall();

        installer.patchPlaceViews(5L, List.of(1L), Map.of());

        assertThat(placeViewHolder.get(1L)).isNull();
    }

    /**
     * <b>홀더에는 합쳐진 맵이 한 번에 걸린다 — 옛 값이 잠깐이라도 보이면 안 된다.</b>
     *
     * <p>조회는 {@link CacheWriteLock}을 잡지 않는다. 그래서 설치가 "옛 후보 맵을 먼저 걸고 그
     * 다음에 밀린 패치를 다시 얹는" 순서였다면, 그 두 줄 사이에 들어온 요청은 되돌아간 이름을
     * 본다 — 예외도 로그도 없이 화면에서만 보이는 종류다.
     *
     * <p>홀더를 감시해 <b>걸린 맵마다 그 순간의 이름</b>을 적어 두고, 옛 이름이 한 번이라도
     * 걸렸는지 본다.
     */
    @Test
    void 설치는_밀린_패치를_합친_맵을_한_번에_건다() {
        List<String> namesAtEachSwap = new java.util.ArrayList<>();
        PlaceViewHolder watching = new PlaceViewHolder() {
            @Override
            void replaceAll(ConcurrentMap<Long, PlaceView> fresh) {
                super.replaceAll(fresh);
                PlaceView view = fresh.get(1L);
                namesAtEachSwap.add(view == null ? null : view.name());
            }
        };
        SnapshotInstaller installer = new SnapshotInstaller(
                loader, snapshotBox, watching, tagViewHolder, writeLock);
        given(loader.readSourceState())
                .willReturn(source(4L, 2L, place(1L, "처음이름")))
                .willReturn(source(5L, 2L, place(1L, "옛이름")));
        installer.rebuildAndInstall();

        installer.patchPlaceViews(6L, List.of(1L), Map.of(1L, view(1L, "방금고친이름")));
        installer.rebuildAndInstall();

        assertThat(namesAtEachSwap)
                .as("홀더에 걸린 맵에는 옛 이름이 한 번도 실리지 않는다")
                .containsExactly("처음이름", "방금고친이름");
    }

    /**
     * <b>느리게 도착한 옛 패치가 새 패치를 덮지 않는다.</b> 두 어드민 수정이 잇따르면 뒤엣것이
     * 먼저 읽기를 마칠 수 있다. 설치한 시점과만 비교하면 둘 다 그보다 새것이라 통과하고, 나중에
     * 도착한 <b>옛</b> 값이 화면에 남는다.
     */
    @Test
    void 더_새_패치가_이미_있으면_옛_패치는_얹지_않는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState()).willReturn(source(4L, 2L, place(1L, "처음이름")));
        installer.rebuildAndInstall();

        installer.patchPlaceViews(7L, List.of(1L), Map.of(1L, view(1L, "새패치")));
        installer.patchPlaceViews(6L, List.of(1L), Map.of(1L, view(1L, "늦게도착한옛패치")));

        assertThat(placeViewHolder.get(1L).name()).isEqualTo("새패치");
    }

    /** 태그 맵도 같은 규율을 따른다 — 더 새 태그 패치가 있으면 옛것을 얹지 않는다. */
    @Test
    void 더_새_태그_패치가_이미_있으면_옛_태그_패치는_얹지_않는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState()).willReturn(source(4L, 2L, place(1L, "밀크티집")));
        installer.rebuildAndInstall();

        installer.patchTagViews(7L, Map.of(9L, new TagView(9L, "새태그이름", true)));
        installer.patchTagViews(6L, Map.of(9L, new TagView(9L, "늦게도착한옛이름", true)));

        assertThat(tagViewHolder.get(9L).name()).isEqualTo("새태그이름");
    }

    /**
     * <b>"지금 어느 시점을 싣고 있나"의 답은 스냅샷 하나뿐이다.</b> 번호를 별도 필드로 복제해 두면
     * 요청이 상자와 그 필드를 따로 읽어 서로 다른 설치 상태를 볼 수 있다.
     */
    @Test
    void 설치한_시점은_언제나_상자의_스냅샷에서_나온다() {
        SnapshotInstaller installer = installer();
        assertThat(installer.installedRevision()).isEqualTo(SnapshotMetadata.NOT_INSTALLED);
        assertThat(installer.installedCursorVersion()).isEqualTo(SnapshotMetadata.NOT_INSTALLED);

        given(loader.readSourceState()).willReturn(source(5L, 3L, place(1L, "밀크티집")));
        installer.rebuildAndInstall();

        assertThat(installer.installedRevision()).isEqualTo(snapshotBox.current().revision());
        assertThat(installer.installedCursorVersion())
                .isEqualTo(snapshotBox.current().cursorVersion());
    }

    /** 태그 맵도 같은 규율을 따른다 — 리빌드보다 새 패치는 설치 뒤에 다시 얹힌다. */
    @Test
    void 태그_패치도_리빌드에_되감기지_않는다() {
        SnapshotInstaller installer = installer();
        given(loader.readSourceState())
                .willReturn(source(4L, 2L, place(1L, "밀크티집")))
                .willReturn(source(5L, 2L, place(1L, "밀크티집")));
        installer.rebuildAndInstall();

        installer.patchTagViews(6L, Map.of(9L, new TagView(9L, "방금고친태그", true)));
        installer.rebuildAndInstall();

        assertThat(tagViewHolder.get(9L).name()).isEqualTo("방금고친태그");
    }

    // === fixtures ===

    /**
     * 한 시점의 원본 한 벌. 엔트리에는 이름 칸이 없으므로(표시값은 스냅샷 밖이다) 픽스처가
     * 장소 하나를 엔트리와 표시값 두 조각으로 만든다.
     */
    private record Row(long placeId, String name) {
    }

    private static SnapshotLoader.SourceState source(
            long revision, long cursorVersion, Row... rows) {
        List<PlaceEntry> entries = new java.util.ArrayList<>(rows.length);
        ConcurrentMap<Long, PlaceView> views = new ConcurrentHashMap<>();
        for (Row row : rows) {
            entries.add(new PlaceEntry(
                    row.placeId(), 100L, 0L, 1.0, 1_700_000_000L, 0L, 0L, 0, 37.5, 127.0));
            views.put(row.placeId(), view(row.placeId(), row.name()));
        }
        return new SnapshotLoader.SourceState(
                new SnapshotMetadata(revision, cursorVersion),
                entries, views, Map.of(9L, new TagView(9L, "원본태그", true)));
    }

    private static Row place(long placeId, String name) {
        return new Row(placeId, name);
    }

    private static PlaceView view(long placeId, String name) {
        return new PlaceView(placeId, name, "thumb/" + placeId, 9L);
    }
}
