package org.sopt.solply_server.domain.place.cache.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;
import org.sopt.solply_server.support.TestMeters;
import org.sopt.solply_server.domain.place.cache.PlaceEntry;
import org.sopt.solply_server.domain.place.cache.PlaceView;
import org.sopt.solply_server.domain.place.cache.town.TownDbDirectReader.Page;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.repository.querydsl.PlaceListDbQueryRepository;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.place.util.TagMasks;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB 직접 조회 구조({@link TownDbDirectReader})와 동네 캐시 구조의 <b>등가 게이트</b>.
 *
 * <p>대조군은 같은 픽스처를 {@link TownSourceLoader}로 적재해 {@link TownListReader}로 자른
 * 페이지다. 두 경로가 실제로 DB를 돌며(대조군은 동네 전량 적재, 시험군은 페이지 행만 SQL 절단)
 * 같은 순서·같은 집합을 내야 한다. 기대 순서를 손으로 적지 않는 이유는 두 경로가 함께 틀리는
 * 그린을 막기 위해서다 — 순서 자체의 정본은 {@code PlaceListFlowIT}이 값으로 물고 있다.
 *
 * <p><b>픽스처가 겨누는 갈림길.</b>
 * <ul>
 *   <li><b>동점 구간</b> — 북마크 4·4·4·2가 두 동네에 걸쳐 있다. 타이브레이크 방향이 어긋나면
 *       페이지 경계에서 갈린다.</li>
 *   <li><b>같은 초에 만든 둘</b> — 최신순만 id 타이브레이크가 <b>내림차순</b>이다.</li>
 *   <li><b>같은 평점·다른 리뷰 수 셋</b> — 평점순만 seek이 2단이고, 동점이 둘뿐이면 안쪽 칸이
 *       뒤집혀도 경계가 우연히 맞는다. 그래서 셋이다.</li>
 *   <li><b>리뷰 0건</b> — 평점 0으로 맨 뒤에 선다 (V37).</li>
 *   <li><b>장소가 없는 동네</b> — 빈 결과와 "범위에 들어는 있음"을 가른다.</li>
 * </ul>
 */
@SpringBootTest
class TownDbDirectReaderIT extends MySqlContainerSupport {

    /** 계측은 이 파일의 검증 대상이 아니다 — 호출을 채우기만 한다. */
    private static final PlaceListMeters METERS = TestMeters.noop();


    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void dbDirectReaderProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    /** 뒷정리가 픽스처를 역추적하는 유일한 기준점. 다른 IT의 접두사와 겹치면 안 된다 */
    private static final String TOWN_NAME_PREFIX = "DB직접IT동네";
    private static final String USER_NICKNAME_PREFIX = "DB직접IT유저";

    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 30, 2, 0, 0);

    /** 태그 픽스처는 V2 시드의 계층을 그대로 빌린다 (서브 태그의 parent = 메인 태그) */
    private static final long SEED_MAIN_TAG = 1L;
    private static final long SEED_OPTION1_A = 7L;
    private static final long SEED_OPTION1_B = 8L;

    /** 정적 다섯 축 — 거리순은 이 구조의 범위 밖이다 */
    private static final List<PlaceSortType> STATIC_SORTS = List.of(
            PlaceSortType.POPULAR, PlaceSortType.LATEST, PlaceSortType.RATING,
            PlaceSortType.REVIEW_COUNT, PlaceSortType.BOOKMARK_COUNT);

    @Autowired private TownDbDirectReader reader;
    @Autowired private TownVersionRepository versionRepository;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    /** 걸림쇠를 두른 reader를 세울 때만 쓴다 — 그 밖에는 주입받은 reader가 돈다 */
    @Autowired private PlaceListDbQueryRepository dbQueryRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NamedParameterJdbcTemplate namedJdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private long townA;
    private long townB;
    /** 장소가 하나도 없는 동네 — 빈 결과와 범위 포함을 가르는 자리 */
    private long townEmpty;

    private long a1;

    @BeforeEach
    void setUp() {
        // 회차마다 동네를 새로 만든다 — 이 클래스는 롤백하지 않아 픽스처가 쌓인다
        long city = createTown(TOWN_NAME_PREFIX + "시", null);
        townA = createTown(TOWN_NAME_PREFIX + "가", city);
        townB = createTown(TOWN_NAME_PREFIX + "나", city);
        townEmpty = createTown(TOWN_NAME_PREFIX + "다", city);

        a1 = createPlace(townA, "직접A", CALCULATED_AT.minusDays(3), 37.5010, 127.0010);
        long a2 = createPlace(townA, "직접B", CALCULATED_AT.minusDays(2), 37.5100, 127.0100);
        // a2와 같은 초 — 최신순의 id 내림차순 타이브레이크를 겨눈다. 좌표는 일부러 비운다
        createPlace(townA, "직접C", CALCULATED_AT.minusDays(2), null, null);
        long a4 = createPlace(townA, "직접D", CALCULATED_AT.minusDays(1), 37.5200, 127.0200);
        long b1 = createPlace(townB, "직접E", CALCULATED_AT.minusDays(3), 37.4900, 126.9900);
        createPlace(townB, "직접F", CALCULATED_AT.minusDays(1), 37.4800, 126.9800);
        // a1과 평점이 같고 리뷰 수만 다른 둘 — 평점순 2단 seek의 안쪽 칸을 겨눈다
        long a5 = createPlace(townA, "직접H", CALCULATED_AT.minusDays(2), 37.5150, 127.0150);
        long a6 = createPlace(townA, "직접I", CALCULATED_AT.minusDays(2), 37.5160, 127.0160);

        linkTags(a1, SEED_MAIN_TAG, SEED_OPTION1_A);
        linkTags(a2, SEED_MAIN_TAG, SEED_OPTION1_B);
        linkTags(a4, SEED_MAIN_TAG, SEED_OPTION1_A);
        linkTags(b1, SEED_MAIN_TAG, SEED_OPTION1_A);

        // 북마크 4·4·4·2 — 동점 셋이 두 동네에 걸친다
        for (int i = 0; i < 4; i++) {
            insertBookmark(createUser(), a1);
            insertBookmark(createUser(), a2);
            insertBookmark(createUser(), b1);
        }
        for (int i = 0; i < 2; i++) {
            insertBookmark(createUser(), a4);
        }

        for (int i = 0; i < 3; i++) {
            insertReview(createUser(), a1, 5);
            insertReview(createUser(), a2, 1);
        }
        for (int i = 0; i < 2; i++) {
            insertReview(createUser(), b1, 3);
            insertReview(createUser(), a5, 5);
        }
        insertReview(createUser(), a6, 5);

        // 행을 짓는 것은 운영에서 어드민 쓰기 트랜잭션의 몫이다 — 이 픽스처는 재구축 문장으로
        // 그 자리를 채운다
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
    }

    /**
     * <b>정적 다섯 축의 첫 페이지가 두 경로에서 같다.</b> 게이트의 본체다.
     */
    @Test
    void 정적_다섯_축의_첫_페이지가_동네_캐시_경로와_같다() {
        for (PlaceSortType sort : STATIC_SORTS) {
            Page page = read(List.of(townA), sort, null, null, 2);

            assertThat(ids(page)).as("%s - 1페이지가 비면 게이트가 공허하다", sort).isNotEmpty();
            assertThat(ids(page)).as("%s - 1페이지 id 순서", sort)
                    .isEqualTo(cachePathIds(List.of(townA), sort, null, null, 2));
        }
    }

    /**
     * <b>커서로 이어 간 두 번째 페이지도 같다.</b> seek 술어(등호 분기 포함)는 2페이지에서만
     * 돌므로, 1페이지만 보면 keyset 계약이 검증되지 않는다.
     *
     * <p>커서는 1페이지 <b>마지막 행</b>에서 발급해 두 경로에 같은 것을 넣는다 — 지어낸 커서로는
     * 발급 → 해석 → 재개의 왕복이 빠진다.
     */
    @Test
    void 정적_다섯_축의_두번째_페이지가_두_경로에서_같다() {
        for (PlaceSortType sort : STATIC_SORTS) {
            Page page1 = read(List.of(townA), sort, null, null, 2);
            PlaceListCursor cursor = cursorAfter(page1, sort, null);

            Page page2 = read(List.of(townA), sort, null, cursor, 2);

            assertThat(ids(page2)).as("%s - 2페이지가 비면 seek을 못 본다", sort).isNotEmpty();
            assertThat(ids(page2)).as("%s - 2페이지 id 순서", sort)
                    .isEqualTo(cachePathIds(List.of(townA), sort, null, cursor, 2));
            assertThat(ids(page1)).as("%s - 두 페이지가 겹친다", sort)
                    .doesNotContainAnyElementsOf(ids(page2));
        }
    }

    /**
     * <b>태그 필터를 건 요청도 같다.</b> 세 그룹이 AND로 엮이는 규칙({@code TagMasks})을 SQL 술어와
     * 자바 판정이 공유하는지가 여기서 드러난다 — 그룹을 한 마스크로 합치면 OR가 되어 결과 집합이
     * 통째로 넓어진다.
     *
     * <p>페이지 크기를 1로 두는 것은 필터 통과 장소가 둘(a1·a4)이라 커서가 실제로 발급·소비되게
     * 하기 위해서다.
     */
    @Test
    void 태그_필터를_건_두_페이지가_두_경로에서_같다() {
        for (PlaceSortType sort : STATIC_SORTS) {
            Page page1 = read(List.of(townA), sort, SEED_OPTION1_A, null, 1);

            assertThat(ids(page1)).as("%s - 필터 1페이지", sort)
                    .isNotEmpty()
                    .isEqualTo(cachePathIds(List.of(townA), sort, SEED_OPTION1_A, null, 1));

            PlaceListCursor cursor = cursorAfter(page1, sort, SEED_OPTION1_A);
            Page page2 = read(List.of(townA), sort, SEED_OPTION1_A, cursor, 1);

            assertThat(ids(page2)).as("%s - 필터 2페이지", sort)
                    .isNotEmpty()
                    .isEqualTo(cachePathIds(List.of(townA), sort, SEED_OPTION1_A, cursor, 1));
        }
    }

    /**
     * <b>여러 동네의 합집합도 같다.</b> DB는 동네별 브랜치를 합쳐 전역 순서를 만들고, 캐시 경로는
     * 동네별 배열을 힙으로 병합한다. 두 전순서가 같아야 하는데 그것을 보장하는 것은 타이브레이크까지
     * 포함한 비교자 하나뿐이라, 방향이 하나만 어긋나도 동점 구간에서 갈린다.
     *
     * <p>장소가 없는 동네를 범위에 함께 넣는다 — 빈 브랜치가 순서를 흔들면 안 된다.
     */
    @Test
    void 여러_동네_합집합이_두_경로에서_같다() {
        List<Long> leaves = List.of(townA, townB, townEmpty);
        for (PlaceSortType sort : STATIC_SORTS) {
            Page page1 = read(leaves, sort, null, null, 3);

            assertThat(ids(page1)).as("%s - 다중 동네 1페이지", sort)
                    .isNotEmpty()
                    .isEqualTo(cachePathIds(leaves, sort, null, null, 3));

            PlaceListCursor cursor = cursorAfter(page1, sort, null);
            Page page2 = read(leaves, sort, null, cursor, 3);

            assertThat(ids(page2)).as("%s - 다중 동네 2페이지", sort)
                    .isNotEmpty()
                    .isEqualTo(cachePathIds(leaves, sort, null, cursor, 3));
            assertThat(ids(page1)).as("%s - 다중 동네 페이지 중복", sort)
                    .doesNotContainAnyElementsOf(ids(page2));
        }
    }

    /**
     * <b>장소가 없는 동네는 빈 페이지지만 범위에는 들어 있다.</b> 번호에서 빠지면 다음 커서의 범위
     * 표현이 달라져 같은 요청의 스크롤이 조용히 다른 범위가 된다.
     */
    @Test
    void 장소가_없는_동네는_빈_페이지고_번호는_돌아온다() {
        Page page = read(List.of(townEmpty), PlaceSortType.POPULAR, null, null, 10);

        assertThat(page.entries()).isEmpty();
        assertThat(page.displays()).isEmpty();
        assertThat(page.versions().townIds()).containsExactly(townEmpty);
        assertThat(page.versions().scope()).startsWith(PlaceListCursor.TOWN_PREFIX);

        Page multi = read(List.of(townA, townEmpty), PlaceSortType.POPULAR, null, null, 10);
        assertThat(multi.versions().townIds())
                .as("장소가 없는 동네도 범위에 남는다")
                .containsExactlyInAnyOrder(townA, townEmpty);
    }

    /**
     * <b>표시값은 페이지에 실린 행마다 함께 온다.</b> 정렬 키와 표시값이 갈라져 오면 목록에서 행이
     * 조용히 빠지거나 다른 회차의 이름이 붙는다 — 그 "찾지 못함"이라는 상태가 생기지 않는 것이
     * 이 구조의 계약이다.
     */
    @Test
    void 페이지_행마다_표시값과_정렬키가_함께_온다() {
        Page page = read(List.of(townA, townB), PlaceSortType.BOOKMARK_COUNT, null, null, 4);

        assertThat(page.entries()).isNotEmpty();
        assertThat(page.displays().keySet()).containsExactlyInAnyOrderElementsOf(ids(page));
        for (PlaceEntry entry : page.entries()) {
            PlaceView view = page.displays().get(entry.placeId());
            assertThat(view.placeId()).isEqualTo(entry.placeId());
            assertThat(view.name()).isEqualTo(nameOf(entry.placeId()));
            assertThat(entry.townId()).isIn(townA, townB);
        }
        // 정렬 키도 같은 행에서 왔다 — 북마크 수가 저장값과 같아야 한다
        for (PlaceEntry entry : page.entries()) {
            assertThat(entry.bookmarkCount()).isEqualTo(bookmarkCountOf(entry.placeId()));
        }
    }

    /**
     * <b>번호가 오른 뒤의 커서는 만료다.</b> 로컬에 옛 데이터가 남아 있는지와 무관하게, 지금 번호와
     * 다른 범위 표현은 이어 서빙하지 않는다.
     */
    @Test
    void 번호가_오른_뒤의_커서는_만료다() {
        Page page1 = read(List.of(townA), PlaceSortType.LATEST, null, null, 2);
        PlaceListCursor cursor = cursorAfter(page1, PlaceSortType.LATEST, null);

        transactionTemplate.executeWithoutResult(
                status -> versionRepository.bump(List.of(townA)));

        assertThatThrownBy(() -> read(List.of(townA), PlaceSortType.LATEST, null, cursor, 2))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);
    }

    /**
     * <b>범위 자체가 달라져도 만료다.</b> 번호가 하나도 오르지 않아도, 같은 파라미터가 풀린 leaf
     * 집합이 달라지면 탐색 대상이 바뀐 것이다 — 번호만 견주는 구현은 이 자리를 통과시킨다.
     */
    @Test
    void 범위가_달라진_커서는_만료다() {
        Page page1 = read(List.of(townA), PlaceSortType.POPULAR, null, null, 2);
        PlaceListCursor cursor = cursorAfter(page1, PlaceSortType.POPULAR, null);

        assertThatThrownBy(
                () -> read(List.of(townA, townB), PlaceSortType.POPULAR, null, cursor, 2))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_PLACE_CURSOR);
    }

    /**
     * <b>돌려주는 번호는 돌려주는 행의 번호다 — 커밋을 두 문장 사이에 끼워 넣어 확인한다.</b>
     *
     * <p>쓰기를 그냥 동시에 돌리고 읽기를 반복하는 검증은 <b>커밋이 실제로 번호 읽기와 페이지
     * 문장 사이에 들어갔다는 보장이 없어</b>, read view가 없는 구현도 우연히 통과한다. 그래서
     * 번호를 읽는 자리에 걸림쇠를 걸어 순서를 못 박는다.
     * <ol>
     *   <li>reader가 번호를 읽는다(실제 {@link TownVersionRepository} 호출).
     *   <li>그 자리에서 <b>다른 커넥션</b>의 쓰기가 원본 삭제 + 번호 올리기를 한 트랜잭션으로
     *       커밋할 때까지 기다린다.
     *   <li>그 뒤에야 페이지 문장과 표시값 문장이 돈다.
     * </ol>
     * 같은 read view라면 뒤의 두 문장은 커밋을 보지 못한다 — 옛 번호와 옛 원본이 함께 나와야
     * 한다. 문장마다 시점이 갈리는 구현이면 <b>옛 번호 + 사라진 행</b>이 나온다.
     *
     * <p>reader는 같은 클래스를 그대로 쓰되 번호 리포지토리만 걸림쇠를 두른 것으로 바꿔 세운다 —
     * 트랜잭션 설정도 SQL도 운영과 같은 것이 돈다.
     */
    @Test
    void 번호와_행은_같은_read_view에서_나온다() throws Exception {
        long before = read(List.of(townA), PlaceSortType.LATEST, null, null, 50)
                .versions().versionOf(townA);
        assertThat(ids(read(List.of(townA), PlaceSortType.LATEST, null, null, 50)))
                .as("커밋 전에는 그 장소가 보인다").contains(a1);

        CountDownLatch versionRead = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        AtomicReference<Exception> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (Connection con = DriverManager.getConnection(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                con.setAutoCommit(false);
                if (!versionRead.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("번호 읽기를 기다리다 예산을 넘겼다");
                }
                try (PreparedStatement delete = con.prepareStatement(
                        "DELETE FROM place_stats WHERE place_id = ?")) {
                    delete.setLong(1, a1);
                    delete.executeUpdate();
                }
                try (PreparedStatement bump = con.prepareStatement("""
                        INSERT INTO place_list_town_versions (town_id, version)
                        SELECT t.id, 1 FROM towns t WHERE t.id = ?
                        ON DUPLICATE KEY UPDATE version = place_list_town_versions.version + 1
                        """)) {
                    bump.setLong(1, townA);
                    bump.executeUpdate();
                }
                con.commit();
            } catch (Exception e) {
                writerFailure.set(e);
            } finally {
                committed.countDown();
            }
        }, "db-direct-reader-writer");
        writer.start();

        // 번호를 읽은 <b>직후</b> 쓰기를 깨우고, 그 커밋이 끝날 때까지 이 트랜잭션을 붙잡는다.
        // 리포지토리를 그대로 상속해 실제 문장은 super가 돌린다 — 가짜 데이터로 바꾸지 않는다.
        TownVersionRepository paused =
                new TownVersionRepository(jdbcTemplate, namedJdbcTemplate) {
                    @Override
                    public TownVersions readInCurrentTransaction(Collection<Long> townIds) {
                        TownVersions versions = super.readInCurrentTransaction(townIds);
                        versionRead.countDown();
                        try {
                            if (!committed.await(20, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("쓰기 커밋을 기다리다 예산을 넘겼다");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return versions;
                    }
                };

        TownDbDirectReader pausedReader = new TownDbDirectReader(
                entityManager, paused, dbQueryRepository, transactionManager);
        Page page = pausedReader.read(List.of(townA), PlaceSortType.LATEST,
                null, null, null, null, 50);

        writer.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(writerFailure.get()).as("쓰기 쪽이 실패했다").isNull();

        assertThat(page.versions().versionOf(townA)).as("읽은 번호").isEqualTo(before);
        assertThat(ids(page)).as("옛 번호인데 커밋된 삭제가 보인다").contains(a1);

        // 그 커밋이 실제로 일어났다는 증거 — 다음 읽기는 새 번호와 사라진 행을 함께 본다
        Page next = read(List.of(townA), PlaceSortType.LATEST, null, null, 50);
        assertThat(next.versions().versionOf(townA)).as("다음 읽기의 번호").isEqualTo(before + 1);
        assertThat(ids(next)).as("새 번호인데 옛 원본이 보인다").doesNotContain(a1);
    }

    // === helpers ===

    /** 시험군 한 페이지 — 실제 DB 리포지토리 경로를 타는 읽기다 */
    private Page read(List<Long> leafTownIds, PlaceSortType sort, Long option1TagId,
            PlaceListCursor cursor, int fetchSize) {
        return reader.read(leafTownIds, sort,
                option1TagId == null ? null : SEED_MAIN_TAG,
                option1TagId == null ? null : List.of(option1TagId),
                null, cursor, fetchSize);
    }

    /**
     * 대조군 한 페이지 — 같은 픽스처를 동네 단위로 적재해 캐시 경로의 리더로 자른다.
     * 같은 커서·같은 마스크·같은 limit이라 두 경로의 차이는 "어디서 자르는가"뿐이다.
     *
     * <p><b>두 캐시 모양을 여기서 함께 돈다.</b> 적재 모양은 {@code solply.place-list.list-source}가
     * 정하므로 주입받은 로더 하나만 쓰면 이 게이트의 대조군이 기본값에 따라 바뀐다 — 사전 정렬
     * 배열이 없는 모양으로 적재되면 {@link TownListReader#page}가 배열을 찾다 깨진다. 그래서
     * 모양을 고정한 로더를 각각 세워 <b>사전 정렬 경로와 요청 시점 정렬 경로가 서로 같은지</b>도
     * 여기서 물고, 그 하나를 DB 직접 조회의 대조군으로 돌려준다. 기본값이 무엇이든 게이트가
     * 재는 것은 같다.
     */
    private List<Long> cachePathIds(List<Long> leafTownIds, PlaceSortType sort, Long option1TagId,
            PlaceListCursor cursor, int limit) {

        TagMasks masks = TagMasks.of(
                option1TagId == null ? null : SEED_MAIN_TAG,
                option1TagId == null ? null : List.of(option1TagId),
                null);

        List<Long> presorted = TownListReader
                .page(loaderFor(PlaceListProperties.ListSource.TOWN_PRESORTED).load(leafTownIds),
                        sort, masks, cursor, limit, METERS)
                .stream().map(PlaceEntry::placeId).toList();

        List<Long> sortedNow = TownListReader
                .pageBySortingNow(
                        loaderFor(PlaceListProperties.ListSource.TOWN_REQUEST_SORT)
                                .load(leafTownIds),
                        sort, masks, cursor, limit, METERS)
                .stream().map(PlaceEntry::placeId).toList();

        assertThat(sortedNow)
                .as("두 캐시 모양이 갈렸다 - sort=%s", sort)
                .isEqualTo(presorted);
        return presorted;
    }

    /** 적재 모양을 고정한 로더. 런타임 기본값과 무관하게 이 게이트가 같은 것을 재게 한다. */
    private TownSourceLoader loaderFor(PlaceListProperties.ListSource source) {
        PlaceListProperties pinned = new PlaceListProperties();
        pinned.setListSource(source);
        return new TownSourceLoader(
                entityManager, versionRepository, pinned, METERS, transactionManager);
    }

    /**
     * 페이지 마지막 행에서 다음 커서를 만든다. 키 튜플의 자리는 {@code PlaceService}가 싣는 자리와
     * 같아야 한다 — 어긋나면 다음 페이지가 다른 좌표에서 재개된다.
     */
    private PlaceListCursor cursorAfter(Page page, PlaceSortType sort, Long option1TagId) {
        assertThat(page.entries()).as("커서를 만들 행이 없다").isNotEmpty();
        PlaceEntry last = page.entries().get(page.entries().size() - 1);
        return new PlaceListCursor(sort, sortKeys(sort, last), last.placeId(),
                PlaceListCursor.filterPrintOf(
                        null,
                        option1TagId == null ? null : SEED_MAIN_TAG,
                        option1TagId == null ? null : List.of(option1TagId),
                        null),
                page.versions().scope());
    }

    private static List<Double> sortKeys(PlaceSortType sort, PlaceEntry entry) {
        return switch (sort) {
            case POPULAR -> List.of(entry.popularScore());
            case LATEST -> List.of((double) entry.createdAtEpochSecond());
            case RATING -> List.of(entry.ratingToInt() / 100.0, (double) entry.reviewCount());
            case REVIEW_COUNT -> List.of((double) entry.reviewCount());
            case BOOKMARK_COUNT -> List.of((double) entry.bookmarkCount());
            case DISTANCE -> throw new IllegalStateException("거리순은 이 구조의 범위 밖이다");
        };
    }

    private static List<Long> ids(Page page) {
        List<Long> ids = new ArrayList<>(page.entries().size());
        for (PlaceEntry entry : page.entries()) {
            ids.add(entry.placeId());
        }
        return ids;
    }

    private String nameOf(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM place_stats WHERE place_id = ?", String.class, placeId);
    }

    private long bookmarkCountOf(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT bookmark_count FROM place_stats WHERE place_id = ?", Long.class, placeId);
    }

    private long createTown(String name, Long parentId) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, ?, true)", name, parentId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private long createPlace(
            long townId, String name, LocalDateTime createdAt, Double latitude, Double longitude) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at,
                                    latitude, longitude)
                VALUES (?, 'DB직접IT', ?, true, ?, ?, ?)""",
                name, townId, createdAt, latitude, longitude);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    private void linkTags(long placeId, long... tagIds) {
        for (long tagId : tagIds) {
            jdbcTemplate.update(
                    "INSERT INTO place_tag (place_id, tag_id) VALUES (?, ?)", placeId, tagId);
        }
    }

    private static int userSeq = 0;

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + (++userSeq);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    private void insertBookmark(long userId, long placeId) {
        LocalDateTime createdAt = CALCULATED_AT.minusDays(1);
        jdbcTemplate.update("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (?, 'PLACE', ?, ?, ?)""", userId, placeId, createdAt, createdAt);
    }

    private void insertReview(long userId, long placeId, int rating) {
        LocalDateTime createdAt = CALCULATED_AT.minusDays(1);
        jdbcTemplate.update("""
                INSERT INTO place_reviews
                    (user_id, place_id, visited_at, visit_time_slot, content, rating,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'EVENING', 'DB 직접 조회 검증용 리뷰 본문입니다.', ?, ?, ?)""",
                userId, placeId, createdAt.toLocalDate(), rating, createdAt, createdAt);
    }

    /** {@code PlaceListSnapshotEquivalenceIT}과 같은 이유·같은 방식의 뒷정리 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate(
                    "DELETE FROM bookmarks WHERE target_type = 'PLACE' AND target_id IN ("
                            + myPlaces + ")");
            st.executeUpdate("DELETE FROM place_reviews WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM place_list_town_versions WHERE town_id IN ("
                    + myTowns + ")");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX
                    + "%' AND parent_id IS NOT NULL");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
