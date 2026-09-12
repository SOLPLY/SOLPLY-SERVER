package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPayloadCodec;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.place.config.PlaceListSnapshotProperties;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>발행물이 하나도 없는 상태에서 두 인스턴스가 동시에 뜨면 어떻게 되는가</b>(설계 §11-20).
 *
 * <p>배포는 거의 언제나 이 모양이다 — 새 인스턴스 둘이 나란히 올라오고, 그중 아무도 아직
 * 발행물을 만들지 않았다. 둘 다 지으면 <b>발행물이 둘 생기고 커서 회차가 갈려</b> 두 인스턴스가
 * 서로 다른 순서를 서빙한다. <b>설계 의도</b>는 기동 경로가 ShedLock으로 한 대만 짓게 하고
 * 나머지는 그것을 기다렸다가 같은 것을 복원하는 것이다 — 의도대로 갈리는지는 아래 ⚠️를 볼 것.
 *
 * <p><b>실제 기동 경로를 그대로 태운다.</b> CAS만 따로 떼어 보는 대체 검증이 아니다 —
 * {@link SnapshotScheduler#restoreOnStartup()}를 두 노드에서 진짜로 부르고, 그 안의
 * 락 획득·패자 대기·재시도·설치가 실제 MySQL 위에서 돈다. 두 노드는 <b>각자의 설치자</b>
 * (자기 {@link SnapshotBox}·홀더·락)와 <b>각자의 {@code LockProvider}</b>를 갖고(운영에서
 * 인스턴스 둘은 JVM이 둘이다 — {@link #newLockProvider()}), 리포지토리·발행 서비스·
 * {@code DataSource}는 <b>공유</b>한다. 공유되는 심판은 {@code shedlock} 테이블과 포인터 행이고,
 * 그것이 "두 프로세스"의 실질이다.
 *
 * <p><b>스프링 컨텍스트를 둘 띄우지 않는 이유.</b> 노드를 가르는 상태는 설치자가 들고 있는 것이
 * 전부이고(설치 id·정렬 배열·표시값 맵), 경쟁의 심판은 컨텍스트가 아니라 <b>DB의 락 행과 포인터
 * 행</b>이다. 컨텍스트를 하나 더 띄우면 커넥션 풀과 기동 시간만 배로 들고 검증 대상은 같다.
 *
 * <p><b>승자를 락 안에 붙잡아 창을 실제로 벌린다.</b> 그러지 않으면 발행이 워낙 빨라 패자가
 * 기다리는 구간을 밟지 않고 지나가고, 그러면 "패자가 기다렸다가 같은 것을 받는다"를 확인하지
 * 못한 채 초록이 된다.
 *
 * <p><b>⚠️ 관측 — 이 하네스에서 후보 빌드가 두 번 돌았다.</b> 두 노드를 같은 밀리초에
 * 기동시키면 둘 다 원본을 읽어 후보를 짓는 회차가 나온다(5회 중 4회). 노드마다
 * {@code LockProvider}를 따로 줘도 같았다. <b>원인은 규명하지 못했다</b> — 획득이 ShedLock의
 * {@code INSERT IGNORE} 경로였는지 조건부 UPDATE 경로였는지조차 확정하지 못했고, 그것을 가르려면
 * 획득 직후 {@code shedlock} 한 행 전체를 찍는 문장 수준 계측이 필요하다. 그래서 여기에
 * "DB 상호 배제가 깨졌다"고 적지 않는다 — 보지 않은 것을 원인으로 적는 셈이 된다.
 *
 * <p><b>이 하네스가 운영과 다른 점 둘</b>(원인 주장이 아니라, 재현에서 먼저 배제할 후보다).
 * 하나, 여기 사는 {@code LockProvider} 셋(컨텍스트 빈 + 노드 둘)이 전부 같은 {@code locked_by}를
 * 쓴다 — 기본값이 호스트명이고 셋이 한 JVM이다. ShedLock의 해제 문장은
 * {@code WHERE name = ? AND locked_by = ?}뿐이라 이 셋이 구분되지 않는다. 둘, 같은 JVM에 락을
 * 잡지 않고 발행 경로를 부르는 테스트 코드가 있다({@link SnapshotRebuilder},
 * {@code SnapshotPublicationIT}의 {@code publishRound()} 직접 호출). 운영에서 원본을 읽는 자리는
 * 둘뿐이고 둘 다 락 안이다 — {@code SnapshotScheduler}의 부트스트랩과
 * {@code SnapshotPublisher.publishRound()}.
 *
 * <p>그래서 <b>"한 노드만 짓는다"를 단언하지 않는다.</b> 관측이 그 반대를 보였고, 그 성질이
 * 운영에서 성립하는지는 위 미규명 때문에 아직 말할 수 없다. 대신 이 경로가 <b>실제로 지키는
 * 것</b>을 문다 — <b>발행물은 하나뿐이고 두 노드가 같은 것을 복원한다.</b> 그 보장의 주체는 락이
 * 아니라 <b>포인터 CAS</b>이고, 진 쪽은 {@code StalePublicationBaseException}을 받아
 * "최초 발행을 놓쳤다"로 물러난 뒤 이긴 쪽의 발행물을 설치한다. 아래 단언들은 그 관측 회차에서도
 * 전부 통과했다.
 *
 * <p>대가는 정확성이 아니라 <b>중복 비용</b>이다 — 함께 뜬 인스턴스 수만큼 원본 전량 읽기와
 * 인코딩이 헛돌 수 있다. 판단은 메인 담당자 몫으로 남긴다(검증 리포트 §4에 관측과 미규명 범위를
 * 함께 적었다).
 */
@SpringBootTest
class SnapshotBootstrapRaceIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void bootstrapRaceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "부트IT동네";
    private static final String TAG_NAME_PREFIX = "부트IT태그";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 12, 4, 0, 0);
    private static final long TIMEOUT_SECONDS = 30L;

    @Autowired private SnapshotPublisher publisher;
    @Autowired private SnapshotLoader loader;
    @Autowired private SnapshotRebuildRequestRepository rebuildRequestRepository;
    @Autowired private SnapshotPublicationRepository publicationRepository;
    @Autowired private SnapshotPublicationService publicationService;
    @Autowired private SnapshotPayloadCodec codec;
    @Autowired private LockProvider lockProvider;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void givenNothingPublishedYet() {
        long townId = createTown();
        createPlace(townId, "부트IT장소A");
        createPlace(townId, "부트IT장소B");
        createPlace(townId, "부트IT장소C");
        createTag();
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);

        // 이 컨텍스트가 기동 때 지은 발행물을 걷어내 "아직 아무도 발행하지 않았다"를 만든다
        clearPublications();
        drainPendingRequests();
    }

    /**
     * 위 배치 호출들이 올린 재빌드 요청을 닫는다.
     *
     * <p><b>이것이 없으면 이 IT가 제 발에 걸린다.</b> 컨텍스트의 발행자도
     * {@code @Scheduled(fixedDelay)}라 기동 직후 한 번 발화하는데, 그때 밀린 요청이 있으면
     * <b>테스트가 만든 "아무도 발행하지 않은 상태"를 그 발행자가 곧바로 메워 버린다</b>
     * (실측: "아직 아무것도 커밋되지 않았다" 단언이 1L로 깨졌다). 요청을 닫아 두면 발화해도
     * 할 일이 없어 그대로 돌아간다.
     */
    private void drainPendingRequests() {
        rebuildRequestRepository.markProcessed(
                jdbcTemplate.queryForObject(
                        "SELECT requested_seq FROM place_list_rebuild_requests WHERE id = 1",
                        Long.class));
    }

    /**
     * <b>두 노드가 동시에 떠도 발행물은 하나다.</b> 그리고 <b>진 쪽은 지어 올리지 않고 기다렸다가
     * 이긴 쪽의 발행물을 복원한다</b> — 같은 커서 회차, 같은 내용으로.
     *
     * <p>승자가 락을 쥐고 원본을 읽는 동안 <b>아무도 준비되지 않았음</b>을 그 자리에서 확인한다.
     * 그것이 이 기동 경로의 계약이다 — 스냅샷 없이 트래픽을 받으면 목록이 통째로 비는 오답이 나간다.
     */
    @Test
    void 두_노드가_동시에_떠도_발행물은_하나이고_진_쪽은_같은_것을_복원한다() throws Exception {
        Node nodeA = newNode();
        Node nodeB = newNode();

        AtomicInteger buildEntries = new AtomicInteger();
        CountDownLatch winnerIsBuilding = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        SnapshotPublisher blockingPublisher = blockingOnFirstBuild(
                buildEntries, winnerIsBuilding, releaseWinner);
        nodeA.usePublisher(blockingPublisher);
        nodeB.usePublisher(blockingPublisher);

        CyclicBarrier bothAtStartup = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> startA = pool.submit(() -> startUp(nodeA, bothAtStartup));
            Future<?> startB = pool.submit(() -> startUp(nodeB, bothAtStartup));

            assertThat(winnerIsBuilding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("한 노드가 락을 쥐고 원본을 읽는 구간에 들어갔다").isTrue();

            // === 승자가 락 안에 있는 동안 ===
            assertThat(publicationRowCountOnOwnConnection())
                    .as("아직 아무것도 커밋되지 않았다").isZero();
            assertThat(nodeA.installer.installedPublicationId())
                    .as("복원 전에는 준비 완료가 아니다 (A)").isNegative();
            assertThat(nodeB.installer.installedPublicationId())
                    .as("복원 전에는 준비 완료가 아니다 (B)").isNegative();
            assertThat(startA.isDone() || startB.isDone())
                    .as("어느 쪽도 아직 기동을 끝내지 못했다 — 진 쪽은 기다리는 중이다")
                    .isFalse();

            releaseWinner.countDown();
            startA.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            startB.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseWinner.countDown();
            pool.shutdownNow();
        }

        // === 경쟁의 결과 ===
        assertThat(buildEntries.get())
                .as("적어도 한 노드는 원본에서 지었다")
                .isGreaterThanOrEqualTo(1);
        assertThat(publicationRowCountOnOwnConnection())
                .as("발행물은 하나만 생긴다 — 이것이 이 경로가 실제로 보장하는 것이다")
                .isEqualTo(1);

        long installed = currentPublicationIdOnOwnConnection();
        assertThat(nodeA.installer.installedPublicationId()).isEqualTo(installed);
        assertThat(nodeB.installer.installedPublicationId()).isEqualTo(installed);

        assertThat(nodeA.box.current().version())
                .as("커서 회차가 갈리면 두 노드가 서로의 커서를 거절한다")
                .isEqualTo(nodeB.box.current().version());
        assertThat(entryIdsOf(nodeA.box))
                .as("정렬 배열이 담은 원소가 같다")
                .isEqualTo(entryIdsOf(nodeB.box));
        assertThat(viewsOf(nodeA)).isEqualTo(viewsOf(nodeB));
        assertThat(nodeA.tags.get(anyTagId())).isEqualTo(nodeB.tags.get(anyTagId()));
    }

    /**
     * <b>남이 락을 쥔 채 발행하지 못하는 상황에서 기동은 조용히 성공하지 않는다.</b> 시간이 다
     * 되면 예외를 던져 <b>기동을 막는다</b> — 빈 스냅샷을 들고 트래픽을 받는 것보다 뜨지 않는
     * 편이 낫다는 것이 이 경로의 결정이다.
     *
     * <p>락을 밖에서 잡아 두어 {@code tryBootstrap}이 계속 지게 만든다.
     */
    @Test
    void 기다리다_시간이_다_되면_기동이_실패한다() {
        Node node = newNode();
        node.properties.setBootstrapTimeoutMs(1L);
        node.properties.setAdoptPollIntervalMs(10L);

        Optional<SimpleLock> heldByOther = lockProvider.lock(new LockConfiguration(
                Instant.now(), SnapshotPublisher.PUBLISH_LOCK,
                Duration.ofMinutes(5), Duration.ZERO));
        assertThat(heldByOther).as("테스트가 먼저 락을 쥐었다").isPresent();
        try {
            assertThatThrownBy(node.scheduler::restoreOnStartup)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("빈 스냅샷으로 뜨지 않는다");

            assertThat(node.installer.installedPublicationId())
                    .as("설치된 것이 없으니 준비 완료가 아니다").isNegative();
            assertThat(publicationRowCountOnOwnConnection())
                    .as("실패한 기동은 발행물을 남기지 않는다").isZero();
        } finally {
            heldByOther.get().unlock();
        }
    }

    /**
     * <b>이미 발행물이 있으면 기동은 짓지 않고 복원만 한다.</b> 위 경쟁의 패자가 밟는 경로와 같고,
     * 배포마다 회차가 갈리지 않는 근거가 이것이다.
     */
    @Test
    void 발행물이_이미_있으면_기동은_짓지_않고_복원만_한다() {
        long existing = new SnapshotRebuilder(
                publisher, publicationRepository, publicationService,
                newNode().installer).publish();

        Node fresh = newNode();
        AtomicInteger builds = new AtomicInteger();
        fresh.usePublisher(countingBuilds(builds));

        fresh.scheduler.restoreOnStartup();

        assertThat(builds.get()).as("원본에서 다시 짓지 않았다").isZero();
        assertThat(fresh.installer.installedPublicationId()).isEqualTo(existing);
        assertThat(publicationRowCountOnOwnConnection()).isEqualTo(1);
    }

    // === 노드 ===

    /**
     * 한 "인스턴스". 노드를 가르는 상태는 설치자가 들고 있는 것이 전부다 — 리포지토리·발행
     * 서비스·락 공급자는 운영과 같이 공유한다.
     */
    private final class Node {
        private final SnapshotBox box = new SnapshotBox();
        private final PlaceViewHolder views = new PlaceViewHolder();
        private final TagViewHolder tags = new TagViewHolder();
        private final SnapshotInstaller installer;
        private final PlaceListSnapshotProperties properties = new PlaceListSnapshotProperties();
        private SnapshotScheduler scheduler;

        private final LockProvider ownLockProvider = newLockProvider();

        private Node() {
            this.installer = new SnapshotInstaller(
                    publicationRepository, codec, box, views, tags, new CacheWriteLock());
            this.properties.setBootstrapTimeoutMs(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            this.properties.setAdoptPollIntervalMs(50L);
            usePublisher(publisher);
        }

        private void usePublisher(SnapshotPublisher nodePublisher) {
            this.scheduler = new SnapshotScheduler(installer, nodePublisher,
                    publicationRepository, publicationService, ownLockProvider, properties);
        }
    }

    private Node newNode() {
        return new Node();
    }

    /**
     * 노드마다 자기 {@code LockProvider}를 준다 — 운영에서 인스턴스 둘은 <b>JVM이 둘</b>이라
     * 공급자 객체도 둘이고, 공유되는 것은 {@code shedlock} <b>테이블</b>이다. 한 객체를 둘이
     * 나눠 쓰면 그 객체의 JVM 내부 상태가 경쟁에 끼어들어 운영과 다른 것을 재게 된다.
     */
    private LockProvider newLockProvider() {
        return new net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider(
                net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
                        .Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }

    private void startUp(Node node, CyclicBarrier bothAtStartup) {
        try {
            bothAtStartup.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("기동 동기화 실패", e);
        }
        node.scheduler.restoreOnStartup();
    }

    /**
     * 원본 읽기 직전에 훅 하나를 끼운 발행자.
     *
     * <p><b>Mockito 스파이를 쓰지 않는다.</b> 발행자 빈은 {@code @Scheduled}·{@code @SchedulerLock}
     * 때문에 AOP 프록시라 {@code Mockito.spy(빈)}이 "Failed to unwrap proxied object"로 죽는다.
     * 진짜 하위 클래스로 덮으면 그 사정과 무관하고, 무엇보다 <b>{@code super}가 그대로 불려</b>
     * 실제 발행 경로가 온전히 돈다.
     */
    private final class LatchedPublisher extends SnapshotPublisher {

        private final Runnable beforeBuild;

        private LatchedPublisher(Runnable beforeBuild) {
            super(loader, codec, publicationRepository, rebuildRequestRepository,
                    publicationService);
            this.beforeBuild = beforeBuild;
        }

        @Override
        PublicationCandidate buildFromSource() {
            beforeBuild.run();
            return super.buildFromSource();
        }
    }

    /**
     * 첫 번째로 원본을 읽는 노드를 <b>락 안에서</b> 붙잡는다. 락을 쥔 쪽만 여기 들어오므로 이
     * 블로킹이 곧 "승자가 아직 발행하지 않았다"를 뜻한다.
     */
    private SnapshotPublisher blockingOnFirstBuild(AtomicInteger entries,
            CountDownLatch building, CountDownLatch release) {
        return new LatchedPublisher(() -> {
            if (entries.incrementAndGet() != 1) {
                return;
            }
            building.countDown();
            try {
                if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("승자를 풀어 주지 않았다");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
    }

    private SnapshotPublisher countingBuilds(AtomicInteger entries) {
        return new LatchedPublisher(entries::incrementAndGet);
    }

    // === 픽스처 ===

    private static List<Long> entryIdsOf(SnapshotBox box) {
        return box.current().sortedPlaces().entries().stream()
                .map(PlaceEntry::placeId).sorted().toList();
    }

    /** 정렬 배열에 실린 장소들의 표시값 — 두 노드가 같은 값을 그리는지 보는 단위다 */
    private static Map<Long, PlaceView> viewsOf(Node node) {
        return node.box.current().sortedPlaces().entries().stream()
                .map(entry -> node.views.get(entry.placeId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(PlaceView::placeId, view -> view));
    }

    private long anyTagId() {
        return jdbcTemplate.queryForObject("SELECT MIN(id) FROM tags", Long.class);
    }

    private void clearPublications() {
        jdbcTemplate.update(
                "UPDATE place_list_publication_pointer SET publication_id = NULL WHERE id = 1");
        jdbcTemplate.update("DELETE FROM place_list_publications");
    }

    // === 독립 커넥션 — 진행 중인 스프링 트랜잭션과 아무 관계가 없다 ===

    private static long publicationRowCountOnOwnConnection() {
        return queryOnOwnConnection("SELECT COUNT(*) FROM place_list_publications");
    }

    private static long currentPublicationIdOnOwnConnection() {
        return queryOnOwnConnection("SELECT COALESCE(publication_id, -1)"
                + " FROM place_list_publication_pointer WHERE id = 1");
    }

    private static long queryOnOwnConnection(String sql) {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("독립 커넥션 조회 실패: " + sql, e);
        }
    }

    private long createTown() {
        jdbcTemplate.update("INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)",
                TOWN_NAME_PREFIX + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    private void createPlace(long townId, String name) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '부트IT', ?, true, ?)""", name, townId, CALCULATED_AT.minusDays(1));
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private void createTag() {
        Long newId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, 'MAIN', NULL, true, 'PLACE')""",
                newId, TAG_NAME_PREFIX + newId);
    }

    /** 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다 */
    /**
     * 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다.
     *
     * <p>컨텍스트의 배경 폴이 아직 같은 테이블을 읽고 있어 정리가 데드락으로 죽은 적이 있다
     * (실측: {@code Deadlock found when trying to get lock}). 정리 실패는 <b>다음 IT의 픽스처를
     * 어지럽히는</b> 실패라 조용히 넘길 수 없으므로 한 번 다시 시도한다.
     */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        try {
            deleteFixtures();
        } catch (Exception firstAttempt) {
            Thread.sleep(500);
            deleteFixtures();
        }
    }

    private static void deleteFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE place_list_publication_pointer SET publication_id = NULL"
                    + " WHERE id = 1");
            st.executeUpdate("DELETE FROM place_list_publications");
            st.executeUpdate("UPDATE place_list_rebuild_requests"
                    + " SET requested_seq = 0, processed_seq = 0 WHERE id = 1");
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM place_images WHERE place_id IN (" + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM tags WHERE name LIKE '" + TAG_NAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
