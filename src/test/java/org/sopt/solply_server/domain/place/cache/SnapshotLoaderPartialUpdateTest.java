package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.util.TagMasks;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 부분 패치({@code SnapshotLoader#patch})의 계약 — <b>언제 읽는가 · 실패하면 어떻게 되는가 ·
 * 무엇을 지키고 무엇을 가는가</b>.
 *
 * <p><b>로더는 진짜다.</b> 목으로 세우는 것은 바깥 자원 넷(EntityManager·Query·발급소·트랜잭션
 * 매니저)뿐이고, 스냅샷 홀더·정렬 배열·표시값 홀더·쓰기 락은 실물을 쓴다. 로더를 목으로 두면
 * "락 → 읽기 → 발급 → 공표"라는 이 클래스의 <b>순서 자체</b>가 검증에서 빠지는데, 아래 셋이 모두
 * 그 순서에 대한 단언이다. 목 로더로 뼈대를 흉내 내는 쪽은 {@code CacheWriteLockTest}가 이미
 * 맡고 있고(그쪽의 관심사는 락·홀더·리프레셔 셋의 상호작용이다), 여기는 <b>로더 자신</b>을 본다.
 *
 * <p><b>세 가지를 문다.</b>
 * <ol>
 *   <li><b>낡은 전량 회차가 나중 패치를 덮지 못한다.</b> 전량 읽기를 래치로 붙잡아 어드민 커밋이
 *       그 사이에 끼는 창을 실제로 벌린다. 패치가 <em>읽기</em>부터 전량 공표 뒤로 밀리는지를
 *       읽는 순간의 {@code snapshotBox.current()}로 확인한다 — 밀리지 않으면 패치는 커밋 전
 *       데이터를 담게 된다.</li>
 *   <li><b>실패하면 아무것도 바뀌지 않는다.</b> 읽기 실패와 발급 실패 둘 다에서 회차도 표시값도
 *       직전 그대로여야 한다. 발급 실패 쪽이 표시값까지 묻는 것은 로더가 <b>발급을 홀더 교체보다
 *       앞에</b> 두기 때문이다 — 순서가 뒤집히면 번호 없는 표시값만 화면에 남는다.</li>
 *   <li><b>표시값만 갈린 수정은 회차를 쓰지 않는다.</b> DB의 정렬 키가 그 사이에 흘렀어도 이 회차
 *       값을 지키고, 갈리는 것은 홀더의 표시값뿐이다. 이것이 없으면 이름 한 번 고칠 때마다 회차가
 *       하나 소모되어 정상 스크롤이 만료된다.</li>
 * </ol>
 *
 * <p>동네 이동·좌표 변경처럼 <b>배열의 모양</b>이 갈리는 경우는 {@code SortedPlacesTest}가 값으로
 * 따로 문다. 여기서 town과 좌표를 헬퍼에 고정해 둔 것은 그래서다 — 이 파일의 변수는 태그·정렬 키·
 * 표시값이다.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotLoaderPartialUpdateTest {

    private static final long TOWN = 1L;
    private static final Double LAT = 37.5;
    private static final Double LNG = 127.0;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    private static final long CREATED_EPOCH_SECOND = CREATED.toEpochSecond(ZoneOffset.UTC);

    /** 회차를 값으로 구분하는 표식 — 어느 회차의 스냅샷인지는 번호 말고 볼 곳이 없다 */
    private static final long SEED_VERSION = 10L;
    private static final long REBUILD_VERSION = 100L;
    private static final long PATCH_VERSION = 200L;

    /** 태그도 같은 이유로 셋을 갈라 둔다 — 직전 회차 · 전량이 읽은 값 · 어드민이 방금 고친 값 */
    private static final long SEED_TAG = 0b1L;
    private static final long REBUILD_TAG = 0b10L;
    private static final long PATCHED_TAG = 0b100L;

    private static final TagMasks NO_FILTER = TagMasks.of(null, null, null);

    /** 스레드가 서로를 기다리다 영영 멈추지 않게 하는 상한 */
    private static final long TIMEOUT_SECONDS = 5L;

    @Mock private EntityManager em;
    @Mock private Query listQuery;
    @Mock private Query changedQuery;
    @Mock private Query tagQuery;
    @Mock private SnapshotVersionIssuer versionIssuer;
    @Mock private PlatformTransactionManager transactionManager;

    /** 넷 다 진짜다 — 이 파일의 단언은 "패치가 실제로 어디에 닿았는가"이므로 값이 필요하다 */
    private final SnapshotBox snapshotBox = new SnapshotBox();
    private final PlaceViewHolder placeViewHolder = new PlaceViewHolder();
    private final TagViewHolder tagViewHolder = new TagViewHolder();
    private final CacheWriteLock writeLock = new CacheWriteLock();

    private SnapshotLoader loader;

    /**
     * 로더의 세 문장을 <b>SQL 본문으로</b> 갈라 준다. 상수가 private이라 다른 손잡이가 없고, 문장이
     * 셋뿐이라 이 정도의 결합은 값을 한다 — 대신 문장을 고쳐 아래 표식이 사라지면 여기가 곧바로
     * {@code AssertionError}를 던지므로, 조용히 엉뚱한 목이 물리는 일은 없다.
     */
    @BeforeEach
    void wire() {
        given(em.createNativeQuery(anyString())).willAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM tags")) {
                return tagQuery;
            }
            if (sql.contains("place_id IN")) {
                return changedQuery;
            }
            if (sql.contains("place_id =")) {
                // 단건 표시값 문장 — 이 파일의 관심사가 아니다. 전량 문장과 원천이 같아
                // 아래 분기에 조용히 물리기 쉬우므로 여기서 먼저 막는다
                throw new AssertionError("이 파일은 단건 표시값 경로를 세우지 않는다 - " + sql);
            }
            if (sql.contains("FROM place_stats")) {
                return listQuery;
            }
            throw new AssertionError("로더가 예상 못 한 문장을 열었다 - " + sql);
        });
        loader = new SnapshotLoader(em, snapshotBox, placeViewHolder, tagViewHolder,
                writeLock, versionIssuer, transactionManager);
    }

    /**
     * <b>전량이 읽는 동안 어드민이 커밋해도, 그 커밋은 전량에 덮이지 않는다.</b>
     *
     * <p>벌리는 창은 이것이다 — 타이머 전량이 원본을 <em>읽기 시작한 뒤</em> 어드민이 태그를 고쳐
     * 커밋하고, 전량은 <b>고치기 전에 읽어 둔 값</b>으로 회차를 공표한다. 패치가 그 공표를 기다리지
     * 않고 먼저 읽어 버리면 패치도 <em>커밋 전</em> 값을 담아, 어드민 수정이 두 경로 모두에서
     * 사라진다.
     *
     * <p>그래서 단언의 축이 <b>패치의 DB 읽기 시점</b>이다 — 읽는 순간의 {@code current()}가 이미
     * 전량 회차여야 한다. 락이 읽기까지 감싸지 않으면(예: 읽고 나서 락을 잡으면) 이 값이 직전
     * 회차로 나온다.
     *
     * <p>스케줄링을 재우기로 맞추지 않는다 — 전량이 읽기 구간에 들어간 것은 래치로, 패치가 락 앞에
     * 줄을 선 것은 스레드 상태로 확인한 뒤에야 전량을 풀어 준다.
     */
    @Test
    void 전량이_읽는_동안_들어온_패치는_전량_위에_얹히고_덮이지_않는다() throws Exception {
        Snapshot seedSnapshot = seed(
                entry(1L, SEED_TAG, 1.0, 0L, 0L, 0),
                entry(2L, SEED_TAG, 2.0, 0L, 0L, 0));

        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Snapshot> currentAtPatchRead = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        given(listQuery.getResultList()).willAnswer(invocation -> {
            reading.countDown();
            await(resume);
            // 어드민이 태그를 고치기 <em>전</em>의 값이다 — 전량은 이것을 담아 공표한다
            return rows(
                    row(1L, REBUILD_TAG, 1.0, CREATED, 0L, 0L, "0.00", "장소1", 7L, "1.jpg"),
                    row(2L, REBUILD_TAG, 2.0, CREATED, 0L, 0L, "0.00", "장소2 옛 이름", 7L, "2.jpg"),
                    row(3L, REBUILD_TAG, 3.0, CREATED, 0L, 0L, "0.00", "이번 회차의 새 장소", 7L, "3.jpg"));
        });
        given(tagQuery.getResultList())
                .willAnswer(invocation -> rows(new Object[]{7L, "카페", true}));
        given(changedQuery.setParameter(anyString(), any())).willReturn(changedQuery);
        given(changedQuery.getResultList()).willAnswer(invocation -> {
            currentAtPatchRead.set(snapshotBox.current());
            return rows(row(2L, PATCHED_TAG, 99.0, CREATED, 99L, 99L, "9.99",
                    "장소2 어드민이 방금 고친 이름", 8L, "2-new.jpg"));
        });
        given(versionIssuer.issue()).willReturn(REBUILD_VERSION, PATCH_VERSION);

        Thread rebuilding = start("rebuild", loader::rebuild, failure);
        assertThat(reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("전량이 읽기 구간에 들어갔다").isTrue();

        Thread patching = start("patch", () -> loader.patch(List.of(2L)), failure);
        // 패치가 락 앞에 실제로 줄을 선 뒤에야 전량을 풀어 준다 — 그러지 않으면 순서가 우연히
        // 맞아 락이 읽기를 감싸지 않아도 통과하는 테스트가 된다
        awaitQueuedOnLock(patching);

        resume.countDown();
        join(rebuilding);
        join(patching);
        assertThat(failure.get()).isNull();

        Snapshot rebuilt = currentAtPatchRead.get();
        assertThat(rebuilt).as("패치가 DB를 읽는 순간 이미 회차가 하나 공표돼 있었다").isNotNull();
        assertThat(rebuilt.version())
                .as("패치의 DB 읽기는 전량 공표 뒤에야 돈다")
                .isEqualTo(REBUILD_VERSION);

        Snapshot published = snapshotBox.current();
        assertThat(published.version()).isEqualTo(PATCH_VERSION);
        assertThat(placeIdsOf(published))
                .as("패치는 직전 회차가 아니라 방금 공표된 전량 회차 위에 얹힌다")
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(entryOf(published, 2L).tagBitmask())
                .as("어드민이 고친 태그가 남는다")
                .isEqualTo(PATCHED_TAG);
        assertThat(entryOf(published, 2L).popularScore())
                .as("정렬 키는 최신 행이 아니라 회차 값이다")
                .isEqualTo(2.0);

        assertThat(entryOf(rebuilt, 2L).tagBitmask())
                .as("공표된 전량 회차는 패치 뒤에도 그대로다")
                .isEqualTo(REBUILD_TAG);
        assertThat(placeIdsOf(rebuilt)).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(placeIdsOf(seedSnapshot))
                .as("직전 회차도 그대로다").containsExactlyInAnyOrder(1L, 2L);
        assertThat(entryOf(seedSnapshot, 2L).tagBitmask()).isEqualTo(SEED_TAG);

        assertThat(placeViewHolder.get(2L).name()).isEqualTo("장소2 어드민이 방금 고친 이름");
        assertThat(placeViewHolder.get(3L).name())
                .as("전량이 새로 담은 장소의 표시값도 남아 있다")
                .isEqualTo("이번 회차의 새 장소");
        assertThat(tagViewHolder.get(7L).name()).isEqualTo("카페");
    }

    /**
     * <b>읽기가 실패하면 아무것도 바뀌지 않는다.</b> 회차도 표시값도 직전 그대로여야 하고, 번호도
     * 소모되지 않아야 한다 — 발급만 하고 공표하지 못하면 그 번호는 영영 빈 회차로 남는다.
     *
     * <p>패치 실패의 대가는 "<b>다음 성공한 전량 재빌드</b>까지 이 수정이 목록에 없다"뿐이다.
     * 상한을 재빌드의 <em>주기</em>가 아니라 <em>성공</em>으로 세는 것이 정확하다 — 전량도 실패할
     * 수 있고, 그때는 그다음 회차로 밀린다. 원본에서 다시 읽어 어긋남을 바로잡는 것이 전량이라,
     * 낡음을 실제로 걷어 가는 것은 도는 것이 아니라 끝까지 간 회차다.
     */
    @Test
    void 패치_읽기가_실패하면_회차도_표시값도_직전_그대로다() {
        Snapshot seedSnapshot = seed(entry(2L, SEED_TAG, 5.0, 2L, 3L, 450));
        seedView(new PlaceView(2L, "옛 이름", "old.jpg", 7L));

        given(changedQuery.setParameter(anyString(), any())).willReturn(changedQuery);
        given(changedQuery.getResultList())
                .willThrow(new PersistenceException("원본 읽기가 끊겼다"));

        assertThatThrownBy(() -> loader.patch(List.of(2L)))
                .isInstanceOf(PersistenceException.class);

        assertThat(snapshotBox.current()).as("참조까지 그대로다").isSameAs(seedSnapshot);
        assertThat(snapshotBox.current().version()).isEqualTo(SEED_VERSION);
        assertThat(entryOf(seedSnapshot, 2L).tagBitmask()).isEqualTo(SEED_TAG);
        assertThat(placeViewHolder.get(2L).name()).isEqualTo("옛 이름");
        verify(versionIssuer, never()).issue();
    }

    /**
     * <b>발급이 실패해도 아무것도 바뀌지 않는다 — 표시값까지.</b>
     *
     * <p>이 단언의 값은 <b>순서</b>에 있다. 로더는 번호를 받은 뒤에 표시값을 홀더에 넣는데, 그
     * 순서가 뒤집히면 발급이 실패한 순간 홀더에는 새 이름이, 스냅샷에는 옛 회차가 남아 <b>번호 없는
     * 표시값</b>이 화면에 걸린다. 다음 전량 회차까지 그 어긋남을 되돌릴 길이 없다.
     *
     * <p>읽기는 성공시키되 태그를 갈아 배열이 실제로 달라지게 두는 것이 전제다 — 그래야 발급이
     * 호출되고, 그 실패가 이 경로를 탄다.
     */
    @Test
    void 버전_발급이_실패하면_회차도_표시값도_직전_그대로다() {
        Snapshot seedSnapshot = seed(entry(2L, SEED_TAG, 5.0, 2L, 3L, 450));
        seedView(new PlaceView(2L, "옛 이름", "old.jpg", 7L));

        given(changedQuery.setParameter(anyString(), any())).willReturn(changedQuery);
        given(changedQuery.getResultList()).willAnswer(invocation -> rows(
                row(2L, PATCHED_TAG, 5.0, CREATED, 2L, 3L, "4.50", "새 이름", 8L, "new.jpg")));
        given(versionIssuer.issue()).willThrow(
                new IllegalStateException("장소 목록 스냅샷 버전 발급이 번호를 돌려주지 않았다"));

        assertThatThrownBy(() -> loader.patch(List.of(2L)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(snapshotBox.current()).as("참조까지 그대로다").isSameAs(seedSnapshot);
        assertThat(entryOf(seedSnapshot, 2L).tagBitmask()).isEqualTo(SEED_TAG);
        assertThat(placeViewHolder.get(2L).name())
                .as("발급이 표시값 교체보다 앞이라 홀더도 손대지 않았다")
                .isEqualTo("옛 이름");
        assertThat(placeViewHolder.get(2L).thumbnailFileKey()).isEqualTo("old.jpg");
    }

    /**
     * <b>실패가 락을 물고 있지 않는다.</b> 어드민 쓰기 하나가 DB 오류로 실패한 뒤 다음 쓰기가
     * 영영 잠들면, 사고 하나가 목록 갱신 전체를 멈춘다.
     *
     * <p>두 번째 패치를 <b>다른 스레드</b>에서 돌리고 join에 상한을 두는 것은 그래서다 — 락이
     * 풀리지 않았다면 여기서 테스트가 멈추는 대신 실패한다.
     */
    @Test
    void 패치가_실패해도_다음_패치는_락을_잡을_수_있다() throws Exception {
        seed(entry(2L, SEED_TAG, 5.0, 2L, 3L, 450));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        given(changedQuery.setParameter(anyString(), any())).willReturn(changedQuery);
        given(changedQuery.getResultList())
                .willThrow(new PersistenceException("원본 읽기가 끊겼다"))
                .willAnswer(invocation -> rows(
                        row(2L, PATCHED_TAG, 5.0, CREATED, 2L, 3L, "4.50", "새 이름", 8L, "new.jpg")));
        given(versionIssuer.issue()).willReturn(PATCH_VERSION);

        assertThatThrownBy(() -> loader.patch(List.of(2L)))
                .isInstanceOf(PersistenceException.class);

        join(start("patch-again", () -> loader.patch(List.of(2L)), failure));

        assertThat(failure.get()).isNull();
        assertThat(snapshotBox.current().version()).isEqualTo(PATCH_VERSION);
        assertThat(entryOf(snapshotBox.current(), 2L).tagBitmask()).isEqualTo(PATCHED_TAG);
    }

    /**
     * <b>표시값만 갈린 수정은 회차를 쓰지 않는다.</b> 이름·썸네일·메인 태그는 정렬 배열이 보는 값이
     * 아니라 홀더의 몫이라, 배열은 자기 자신으로 남고 발급도 공표도 건너뛴다. 이것이 없으면 어드민이
     * 이름을 고칠 때마다 회차가 하나씩 소모되어 진행 중인 정상 스크롤이 만료된다.
     *
     * <p><b>그 사이에 DB의 정렬 키가 흘렀다는 것이 이 픽스처의 요점이다.</b> 최신 행은 점수 99·
     * 리뷰 99·평점 9.99에 생성일까지 다른 값을 실어 오지만, 배열은 이 회차 값을 지켜야 한다 —
     * 정렬 키는 전량 회차로만 공표되는 값이고, 손댄 장소만 새 기준으로 서면 한 회차 안에서 순서의
     * 근거가 장소마다 갈린다.
     */
    @Test
    void 표시값만_갈린_수정은_회차를_쓰지_않고_정렬_키도_지킨다() {
        Snapshot seedSnapshot = seed(entry(2L, SEED_TAG, 5.0, 2L, 3L, 450));
        seedView(new PlaceView(2L, "옛 이름", "old.jpg", 7L));

        given(changedQuery.setParameter(anyString(), any())).willReturn(changedQuery);
        // 동네·태그·좌표는 그대로고 표시값만 갈렸다. 정렬 키는 그 사이에 배치가 흘려 둔 값이다
        given(changedQuery.getResultList()).willAnswer(invocation -> rows(
                row(2L, SEED_TAG, 99.0, CREATED.plusDays(30), 99L, 99L, "9.99",
                        "새 이름", 8L, "new.jpg")));

        loader.patch(List.of(2L));

        assertThat(snapshotBox.current())
                .as("배열이 볼 값이 그대로라 스냅샷을 새로 짓지 않는다")
                .isSameAs(seedSnapshot);
        assertThat(snapshotBox.current().version()).isEqualTo(SEED_VERSION);
        verify(versionIssuer, never()).issue();

        PlaceEntry kept = entryOf(snapshotBox.current(), 2L);
        assertThat(kept.popularScore()).isEqualTo(5.0);
        assertThat(kept.createdAtEpochSecond()).isEqualTo(CREATED_EPOCH_SECOND);
        assertThat(kept.bookmarkCount()).isEqualTo(2L);
        assertThat(kept.reviewCount()).isEqualTo(3L);
        assertThat(kept.ratingToInt()).isEqualTo(450);
        assertThat(kept.tagBitmask()).isEqualTo(SEED_TAG);

        PlaceView view = placeViewHolder.get(2L);
        assertThat(view.name()).as("갈리는 것은 홀더의 표시값뿐이다").isEqualTo("새 이름");
        assertThat(view.thumbnailFileKey()).isEqualTo("new.jpg");
        assertThat(view.mainTagId()).isEqualTo(8L);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    /**
     * 직전 회차를 손으로 세워 홀더에 넣는다 — 발급소를 태우지 않고 "이미 한 회차가 있다"를 만드는
     * 방법이고, {@code SnapshotBoxTest}가 버전을 손으로 찍는 것과 같은 이유다.
     */
    private Snapshot seed(PlaceEntry... entries) {
        Snapshot snapshot = new Snapshot(SEED_VERSION, SortedPlaces.of(List.of(entries)));
        snapshotBox.adopt(snapshot);
        return snapshot;
    }

    /** 홀더는 넘어온 맵을 복사하지 않고 그대로 쓴다 — 실제 로더처럼 동시 수정이 되는 맵을 준다 */
    private void seedView(PlaceView view) {
        placeViewHolder.replaceAll(new ConcurrentHashMap<>(Map.of(view.placeId(), view)));
    }

    /** 동네와 좌표는 고정이다 — 배열의 모양이 갈리는 경우는 {@code SortedPlacesTest}의 몫 */
    private static PlaceEntry entry(long placeId, long tagBitmask, double popularScore,
            long bookmarkCount, long reviewCount, int ratingToInt) {
        return new PlaceEntry(placeId, TOWN, tagBitmask,
                popularScore, CREATED_EPOCH_SECOND,
                bookmarkCount, reviewCount, ratingToInt,
                LAT, LNG);
    }

    /**
     * 행들을 결과 목록으로 감싼다. <b>{@code List.of(row(...))}를 그대로 쓰면 안 된다</b> — 행이
     * 하나일 때 그 배열이 가변인자로 <em>펼쳐져</em> 열 열세 개짜리 {@code List<Object>}가 되고,
     * 로더는 원소마다 {@code Object[]}로 캐스팅하므로 그 자리에서 {@code ClassCastException}이다.
     */
    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    /**
     * 문장 ①이 낳는 행 하나 — <b>배열의 순서가 곧 로더의 SELECT 목록</b>이다(장소·동네·태그 비트마스크,
     * 점수·생성일, 북마크 수·리뷰 수·평점, 좌표 둘, 이름·메인 태그 id·썸네일 파일 키).
     *
     * <p>평점을 문자열로 받는 것은 컬럼이 {@code DECIMAL(3,2)}이기 때문이다 — 로더가
     * {@code movePointRight(2).intValueExact()}로 무척도 정수를 뽑으므로 스케일이 픽스처에
     * 보여야 한다.
     */
    private static Object[] row(long placeId, long tagBitmask, double popularScore,
            LocalDateTime createdAt, long bookmarkCount, long reviewCount, String avgRating,
            String name, Long mainTagId, String thumbnailFileKey) {
        return new Object[]{
                placeId, TOWN, tagBitmask,
                popularScore, createdAt,
                bookmarkCount, reviewCount, new BigDecimal(avgRating),
                LAT, LNG,
                name, mainTagId, thumbnailFileKey};
    }

    // ── 회차 들여다보기 ────────────────────────────────────────────────────────

    /** 정렬 배열은 조회 API로만 열린다 — 커서 없이 그 동네를 끝까지 훑는다 */
    private static List<PlaceEntry> allOf(Snapshot snapshot) {
        return snapshot.sortedPlaces()
                .page(PlaceSortType.LATEST, List.of(TOWN), NO_FILTER, null, Integer.MAX_VALUE - 1);
    }

    private static PlaceEntry entryOf(Snapshot snapshot, long placeId) {
        return allOf(snapshot).stream()
                .filter(entry -> entry.placeId() == placeId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("version=%d 회차에 place=%d 가 없다"
                        .formatted(snapshot.version(), placeId)));
    }

    private static List<Long> placeIdsOf(Snapshot snapshot) {
        return allOf(snapshot).stream().map(PlaceEntry::placeId).toList();
    }

    // ── 스레드 ────────────────────────────────────────────────────────────────

    private static Thread start(String name, Runnable body, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(body, name);
        thread.setUncaughtExceptionHandler((ignored, thrown) -> failure.set(thrown));
        thread.start();
        return thread;
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        if (thread.isAlive()) {
            throw new IllegalStateException(thread.getName() + " 스레드가 끝나지 않았다");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치가 열리지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 스레드가 쓰기 락 앞에 줄을 설 때까지 기다린다. 재우고 넘겨짚는 것이 아니라 <b>스레드 상태를</b>
     * 본다 — {@code ReentrantLock}은 park로 기다리므로 {@code WAITING}이 되고,
     * {@code SnapshotLoader#patch}가 락 앞에서 하는 일이 빈 목록 검사뿐이라 그 상태는 곧 "락 대기"다.
     */
    private static void awaitQueuedOnLock(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("패치 스레드가 락을 기다리지 않았다");
    }
}
