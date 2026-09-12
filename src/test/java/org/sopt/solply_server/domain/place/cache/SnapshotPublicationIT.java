package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.publication.ProcessedMark;
import org.sopt.solply_server.domain.place.cache.publication.PublicationCandidate;
import org.sopt.solply_server.domain.place.cache.publication.RebuildRequestCounters;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationRepository;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotPublicationService;
import org.sopt.solply_server.domain.place.cache.publication.SnapshotRebuildRequestRepository;
import org.sopt.solply_server.domain.place.cache.publication.StalePublicationBaseException;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>발행 트랜잭션이 실제 MySQL 위에서 원자적인가.</b> 옛 {@code PlaceListVersionIssuerIT}가
 * "번호 발급 테이블"에 대해 묻던 것을 <b>발행물 자체</b>에 대해 다시 묻는다 — 신원을 내주는 곳과
 * 내용을 담는 곳이 한 행으로 합쳐졌기 때문이다(2026-09-12).
 *
 * <p>겨누는 것은 설계 §11의 발행 항목들이다 — 기준을 빌드 <em>전에</em> 잡는가(11), CAS에 진
 * 회차가 고아 행을 남기지 않는가(12), 발행과 처리 표시가 한 트랜잭션인가(13), 빌드 실패가
 * 포인터를 건드리지 않는가(14), 낡은 발행자가 처리 표시를 되돌리지 못하는가(15), 정리가
 * 현재를 지우지 못하고 실패해도 발행이 성공으로 남는가(16), 포인터가 명시인가(17).
 *
 * <p><b>왜 IT인가.</b> 이 기능의 값이 전부 DB 쪽에 있다. AUTO_INCREMENT가 신원을 만들고,
 * {@code REQUIRES_NEW}가 트랜잭션 경계를 만들며, CAS 한 문장이 승자를 정하고, FK가 현재 행을
 * 지킨다. 목으로는 그중 하나도 확인되지 않는다.
 *
 * <p><b>단언은 독립 커넥션으로 읽는다.</b> 스프링 트랜잭션에 붙은 커넥션으로 읽으면 커밋되지
 * 않은 것을 보고 "커밋됐다"고 말하게 된다 — 이 파일이 묻는 것이 정확히 그 경계다.
 */
@SpringBootTest
class SnapshotPublicationIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void publicationProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    private static final String TOWN_NAME_PREFIX = "발행IT동네";
    private static final String TAG_NAME_PREFIX = "발행IT태그";
    private static final String PLACE_NAME = "발행IT장소";
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 12, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = CALCULATED_AT.minusDays(1);

    @SpyBean private SnapshotLoader loader;
    @SpyBean private SnapshotPublicationRepository publicationRepository;
    @Autowired private SnapshotPublisher publisher;
    @Autowired private SnapshotPublicationService publicationService;
    @Autowired private SnapshotRebuildRequestRepository requestRepository;
    @Autowired private SnapshotInstaller installer;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private long placeId;

    @BeforeEach
    void setUp() {
        reset(loader, publicationRepository);
        long townId = createTown();
        placeId = createPlace(townId);
        createTag();

        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        batchProcessor.recalculateCounts(CALCULATED_AT);
        batchProcessor.recalculateScores(CALCULATED_AT);
        // 앞선 테스트가 남긴 요청을 닫아 두고 시작한다 — 각 테스트가 자기 요청만 보게 한다
        drainPendingRequests();
    }

    // === 신원과 포인터 (설계 §11-17·27) ===

    /**
     * <b>구조 발행은 자기 id를 커서 회차로 쓴다.</b> 신원을 내주는 곳과 내용을 담는 곳이 한
     * 행이라 "번호는 받았는데 내용이 없다"가 성립하지 않는다.
     */
    @Test
    void 구조_발행은_자기_id를_커서_회차로_쓴다() {
        long first = publishStructural();
        long second = publishStructural();

        assertThat(second).isGreaterThan(first);
        assertThat(cursorVersionOf(first)).isEqualTo(first);
        assertThat(cursorVersionOf(second)).isEqualTo(second);
    }

    /**
     * <b>표시값만 바뀐 발행은 앞의 커서 회차를 물려받는다.</b> 발행 id는 오르지만 커서는 그대로라,
     * 진행 중인 스크롤이 이름 하나 고친 수정에 끊기지 않는다.
     */
    @Test
    void 표시값_발행은_발행_id만_오르고_커서_회차는_물려받는다() {
        long structural = publishStructural();

        long displayOnly = publishCarrying(structural, structural);

        assertThat(displayOnly).isGreaterThan(structural);
        assertThat(cursorVersionOf(displayOnly))
                .as("커서 회차는 앞 발행의 값을 그대로 이어받는다")
                .isEqualTo(structural);
        assertThat(currentPublicationIdOnOwnConnection()).isEqualTo(displayOnly);
    }

    /**
     * <b>포인터는 명시다 — {@code MAX(id)}가 아니다.</b> 롤백된 INSERT가 AUTO_INCREMENT를 올려
     * 두어도 "현재"는 움직이지 않는다. 발급 순서와 채택 순서는 같은 것이 아니다.
     */
    @Test
    void 롤백된_발행이_올려_둔_번호는_현재로_읽히지_않는다() {
        long current = publishStructural();

        // 기준을 엉뚱하게 대어 CAS를 지게 만든다 — INSERT는 이미 번호를 소비한 뒤다
        assertThatThrownBy(() -> publicationService.publish(
                candidate(null), current + 999, ProcessedMark.none()))
                .isInstanceOf(StalePublicationBaseException.class);

        assertThat(currentPublicationIdOnOwnConnection())
                .as("포인터는 CAS가 정한 그대로다")
                .isEqualTo(current);
    }

    // === 발행 원자성 (설계 §11-12·13) ===

    /**
     * <b>CAS에 진 회차는 고아 행을 남기지 않는다.</b> INSERT는 이미 돌았지만 같은 트랜잭션이라
     * 통째로 롤백된다 — 남으면 정리 배치가 지울 때까지 payload 한 벌이 그냥 쌓인다.
     */
    @Test
    void CAS에_진_회차는_발행물_행을_남기지_않는다() {
        long current = publishStructural();
        long rowsBefore = publicationRowCountOnOwnConnection();

        assertThatThrownBy(() -> publicationService.publish(
                candidate(null), current + 999, ProcessedMark.none()))
                .isInstanceOf(StalePublicationBaseException.class);

        assertThat(publicationRowCountOnOwnConnection())
                .as("트랜잭션 전체가 롤백돼 행이 늘지 않는다")
                .isEqualTo(rowsBefore);
    }

    /**
     * <b>CAS에 진 회차는 처리 표시도 올리지 않는다.</b> 올렸다면 그 요청은 아무도 발행하지 않은
     * 채로 닫혀 <b>변경이 영영 반영되지 않는다.</b>
     */
    @Test
    void CAS에_진_회차는_처리_표시를_올리지_않는다() {
        long current = publishStructural();
        long mySeq = newRequest();
        long processedBefore = counters().processedSeq();

        assertThatThrownBy(() -> publicationService.publish(
                candidate(null), current + 999, ProcessedMark.upTo(mySeq)))
                .isInstanceOf(StalePublicationBaseException.class);

        assertThat(counters().processedSeq()).isEqualTo(processedBefore);
        assertThat(counters().hasPending())
                .as("요청은 열린 채로 남아 다음 회차가 가져간다").isTrue();
    }

    /**
     * <b>발행과 처리 표시가 한 트랜잭션이다.</b> 발행만 되고 표시가 안 된 상태(다음 회차가 같은
     * 내용을 또 짓는다)도, 표시만 되고 발행이 안 된 상태(변경이 유실된다)도 만들어지지 않는다.
     */
    @Test
    void 발행과_처리_표시는_함께_커밋된다() {
        long mySeq = newRequest();

        long publicationId = publicationService.publish(
                candidate(null), currentPublicationIdOnOwnConnection(), ProcessedMark.upTo(mySeq));

        assertThat(currentPublicationIdOnOwnConnection()).isEqualTo(publicationId);
        assertThat(processedSeqOnOwnConnection())
                .as("같은 트랜잭션에서 커밋됐다").isEqualTo(mySeq);
    }

    // === 기준을 빌드 전에 잡는다 (설계 §11-11) ===

    /**
     * <b>빌드 도중 다른 발행이 끼면 낡은 후보가 새 내용을 덮지 않는다.</b> 리뷰가 짚어낸 결함의
     * 회귀 테스트다 — 기준 포인터를 빌드 <em>뒤에</em> 읽으면 그 사이 올라온 발행을 기준으로 삼아
     * CAS에 이겨 버리고, 방금 누가 발행한 내용이 조용히 사라진다.
     *
     * <p>원본 읽기 한가운데에서 다른 인스턴스의 발행을 흉내 내 그 창을 실제로 만든다.
     */
    @Test
    void 빌드_도중_들어온_발행을_낡은_후보가_덮지_않는다() {
        publishStructural();
        newRequest();
        AtomicBoolean interloped = new AtomicBoolean();

        willAnswer(invocation -> {
            Object source = invocation.callRealMethod();
            if (interloped.compareAndSet(false, true)) {
                // 다른 인스턴스가 우리가 읽는 사이에 발행했다
                publicationService.publish(candidate(null),
                        currentPublicationIdOnOwnConnection(), ProcessedMark.none());
            }
            return source;
        }).given(loader).readSourceState();

        long interloperId = currentPublicationIdOnOwnConnectionAfter(() ->
                assertThat(publisher.publishRound())
                        .as("기준이 낡았으므로 이 회차는 발행하지 못한다").isFalse());

        assertThat(currentPublicationIdOnOwnConnection())
                .as("끼어든 발행이 현재로 남는다")
                .isEqualTo(interloperId);
        assertThat(counters().hasPending())
                .as("요청은 닫히지 않아 다음 회차가 다시 짓는다").isTrue();
    }

    // === 빌드 실패 (설계 §11-14) ===

    /**
     * <b>원본 읽기가 던지면 포인터도 처리 표시도 그대로다.</b> 실패한 회차가 남기는 것이 없어야
     * 다음 회차가 같은 자리에서 다시 시작한다.
     */
    @Test
    void 빌드가_실패하면_포인터도_처리_표시도_그대로다() {
        long current = publishStructural();
        newRequest();
        long processedBefore = processedSeqOnOwnConnection();

        willThrow(new IllegalStateException("원본 읽기 실패")).given(loader).readSourceState();

        // 스케줄 진입점은 예외를 삼킨다 — 다음 폴이 다시 시도한다
        publisher.publishIfRequested();

        assertThat(currentPublicationIdOnOwnConnection()).isEqualTo(current);
        assertThat(processedSeqOnOwnConnection()).isEqualTo(processedBefore);
        assertThat(counters().hasPending()).isTrue();
    }

    // === 낡은 발행자 펜싱 (설계 §11-15) ===

    /**
     * <b>ShedLock이 만료돼 깨어난 옛 발행자는 CAS에 지고, 처리 표시를 되돌리지 못한다.</b>
     * 되돌릴 수 있다면 이미 발행된 내용을 다음 회차가 또 짓거나, 더 나쁘게는 낡은 payload가
     * 현재를 덮는다.
     */
    @Test
    void 낡은_발행자의_후보는_CAS에_지고_처리_표시를_되돌리지_못한다() {
        long staleBase = publishStructural();
        // 그 사이 새 발행자가 한 번 더 발행했다
        long fresh = publishStructural();
        long processedAfterFresh = markProcessed(newRequest());

        // 낡은 발행자가 이제야 깨어나 자기 기준으로 CAS를 시도한다
        assertThatThrownBy(() -> publicationService.publish(
                candidate(null), staleBase, ProcessedMark.upTo(processedAfterFresh - 1)))
                .isInstanceOf(StalePublicationBaseException.class);

        assertThat(currentPublicationIdOnOwnConnection()).isEqualTo(fresh);
        assertThat(processedSeqOnOwnConnection())
                .as("처리 표시는 뒤로 가지 않는다")
                .isEqualTo(processedAfterFresh);
    }

    /**
     * <b>처리 표시는 단조다.</b> 표시 문장 자체가 {@code processed_seq < ?}를 달고 있어, 늦게
     * 도착한 작은 값이 앞선 표시를 지우지 못한다.
     */
    @Test
    void 처리_표시는_작은_값으로_되돌아가지_않는다() {
        long high = markProcessed(newRequest());

        requestRepository.markProcessed(high - 1);

        assertThat(processedSeqOnOwnConnection()).isEqualTo(high);
    }

    // === 정리 (설계 §11-16) ===

    /**
     * <b>발행 뒤 옛 행은 지워지고 현재 행은 남는다.</b> 현재를 지키는 것은 코드가 아니라 포인터의
     * FK다 — 정리 문장이 실수로 현재를 고르면 DB가 거절한다.
     */
    @Test
    void 정리는_옛_행만_지우고_현재는_FK가_지킨다() {
        publishStructural();
        long current = publishStructural();

        publicationService.cleanUpQuietly(current);

        assertThat(publicationRowCountOnOwnConnection()).isEqualTo(1);
        assertThat(currentPublicationIdOnOwnConnection()).isEqualTo(current);
    }

    /**
     * <b>정리 실패가 발행을 되돌리지 않는다.</b> 발행은 이미 커밋됐고 정리는 다음 회차가 다시
     * 한다 — 여기서 예외가 새면 성공한 회차가 실패로 기록되고 배치가 헛되이 재시도한다.
     */
    @Test
    void 정리가_실패해도_발행은_성공으로_남는다() {
        long current = publishStructural();
        willThrow(new IllegalStateException("정리 실패"))
                .given(publicationRepository).deleteOlderThan(current);

        publicationService.cleanUpQuietly(current);   // 예외를 흘리지 않는다

        assertThat(currentPublicationIdOnOwnConnection()).isEqualTo(current);
    }

    // === 요청 카운터 (설계 §11-8·9·10) ===

    /**
     * <b>{@code request()}는 트랜잭션 밖에서 거절된다.</b> {@code MANDATORY}가 그것을 강제한다 —
     * 트랜잭션 없이 올린 요청은 통계 쓰기와 원자적이지 않아, 롤백된 회차가 요청만 남긴다.
     */
    @Test
    void 트랜잭션_밖의_요청은_거절된다() {
        assertThatThrownBy(() -> requestRepository.request())
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /**
     * <b>롤백된 트랜잭션은 요청을 남기지 않는다.</b> 요청이 통계 쓰기와 같은 트랜잭션에 있다는
     * 것이 이 설계의 전제이고, 그 값어치가 곧 이 단언이다.
     */
    @Test
    void 롤백된_트랜잭션은_요청을_남기지_않는다() {
        long before = requestedSeqOnOwnConnection();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            requestRepository.request();
            throw new IllegalStateException("이 회차는 죽는다");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(requestedSeqOnOwnConnection()).isEqualTo(before);
    }

    /**
     * <b>코얼레싱 — 빌드 중에 들어온 요청은 다음 회차 몫이다.</b> 한 회차가 자기가 본 값까지만
     * 처리 표시를 올리므로, 그 뒤에 올라온 요청은 다음 회차가 한 번에 가져간다.
     */
    @Test
    void 밀린_요청_여럿을_한_회차가_한_번에_처리한다() {
        publishStructural();
        newRequest();
        newRequest();
        long lastSeq = newRequest();

        assertThat(publisher.publishRound()).isTrue();

        assertThat(processedSeqOnOwnConnection())
                .as("자기가 본 값까지 한 번에 닫는다").isEqualTo(lastSeq);
        assertThat(counters().hasPending()).isFalse();
        assertThat(publisher.publishRound())
                .as("밀린 요청이 없으면 발행하지 않는다").isFalse();
    }

    /**
     * <b>어드민의 처리 표시는 남의 요청을 닫지 않는다.</b> 통계 요청이 밀린 상태에서 어드민이
     * 자기 번호로 닫으려 하면 조건이 맞지 않아 표시가 오르지 않는다 — 어드민은 원본 전량이 아니라
     * 자기 스냅샷에 패치만 얹으므로, 닫아 버리면 통계 변경이 영영 발행되지 않는다.
     */
    @Test
    void 어드민의_표시는_앞에_밀린_요청이_있으면_닫지_않는다() {
        newRequest();                       // 통계가 먼저 요청했다
        long adminSeq = newRequest();       // 어드민이 그 뒤에 요청했다

        boolean closed = requestRepository.markProcessedIfSolelyMine(adminSeq);

        assertThat(closed).isFalse();
        assertThat(counters().hasPending())
                .as("발행자 회차가 통계 변경까지 반영해야 한다").isTrue();
    }

    /** 앞에도 뒤에도 아무도 없으면 자기 요청을 닫는다 — 그 경우 발행자의 전량 재빌드가 돌지 않는다 */
    @Test
    void 어드민의_표시는_조용할_때만_자기_요청을_닫는다() {
        long adminSeq = newRequest();

        boolean closed = requestRepository.markProcessedIfSolelyMine(adminSeq);

        assertThat(closed).isTrue();
        assertThat(counters().hasPending()).isFalse();
    }

    // === 무부하 폴 (설계 §11-25) ===

    /** 밀린 요청이 없으면 발행 폴은 포인터도 읽지 않는다 */
    @Test
    void 요청이_없으면_발행_폴은_포인터를_읽지_않는다() {
        publishStructural();
        drainPendingRequests();
        reset(publicationRepository);

        assertThat(publisher.publishRound()).isFalse();

        org.mockito.Mockito.verify(publicationRepository, org.mockito.Mockito.never())
                .readCurrentPublicationId();
    }

    // === 픽스처 ===

    private long publishStructural() {
        long id = publicationService.publish(
                candidate(null), currentPublicationIdOnOwnConnection(), ProcessedMark.none());
        return id;
    }

    private long publishCarrying(long base, long carriedCursorVersion) {
        return publicationService.publish(
                candidate(carriedCursorVersion), base, ProcessedMark.none());
    }

    /** 실제 원본에서 지은 후보 — payload 내용 자체는 이 파일의 단언이 아니다 */
    private PublicationCandidate candidate(Long carriedCursorVersion) {
        PublicationCandidate built = publisher.buildFromSource();
        if (carriedCursorVersion == null) {
            return built;
        }
        return new PublicationCandidate(carriedCursorVersion, built.formatVersion(),
                built.entryCount(), built.payloadSha256(), built.payload());
    }

    /** {@code request()}가 {@code MANDATORY}라 트랜잭션을 열어 준다 */
    private long newRequest() {
        return transactionTemplate.execute(status -> requestRepository.request());
    }

    private long markProcessed(long seq) {
        requestRepository.markProcessed(seq);
        return seq;
    }

    private RebuildRequestCounters counters() {
        return requestRepository.readCounters();
    }

    /** 앞선 테스트가 남긴 요청을 닫는다 — 커밋되는 IT라 카운터가 클래스 안에서 이어진다 */
    private void drainPendingRequests() {
        requestRepository.markProcessed(requestedSeqOnOwnConnection());
    }

    private long currentPublicationIdOnOwnConnectionAfter(Runnable action) {
        long before = currentPublicationIdOnOwnConnection();
        action.run();
        long after = currentPublicationIdOnOwnConnection();
        return after == before ? before : after;
    }

    private Long cursorVersionOf(long publicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT cursor_version FROM place_list_publications WHERE id = ?",
                Long.class, publicationId);
    }

    // === 독립 커넥션 — 진행 중인 스프링 트랜잭션과 아무 관계가 없다 ===

    private long currentPublicationIdOnOwnConnection() {
        return queryOnOwnConnection(
                "SELECT COALESCE(publication_id, 0) FROM place_list_publication_pointer WHERE id = 1");
    }

    private long publicationRowCountOnOwnConnection() {
        return queryOnOwnConnection("SELECT COUNT(*) FROM place_list_publications");
    }

    private long processedSeqOnOwnConnection() {
        return queryOnOwnConnection(
                "SELECT processed_seq FROM place_list_rebuild_requests WHERE id = 1");
    }

    private long requestedSeqOnOwnConnection() {
        return queryOnOwnConnection(
                "SELECT requested_seq FROM place_list_rebuild_requests WHERE id = 1");
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

    private long createPlace(long townId) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '발행IT', ?, true, ?)""", PLACE_NAME, townId, PLACE_CREATED_AT);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
    }

    /** 태그 id가 곧 비트마스크의 자리라 auto-increment에 맡기지 않는다 (V34) */
    private long createTag() {
        Long newId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM tags", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
                VALUES (?, ?, 'MAIN', NULL, true, 'PLACE')""",
                newId, TAG_NAME_PREFIX + newId);
        return newId;
    }

    /** 이 IT는 롤백되지 않으므로(@SpringBootTest는 기본 커밋) 만든 행을 직접 지운다 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
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
