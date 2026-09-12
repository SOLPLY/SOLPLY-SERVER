package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.reset;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.bookmark.repository.BookmarkCountEventRepository;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor.DeltaResult;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;

/**
 * 북마크 델타 소비의 <b>원자성</b>을 실제 MySQL에서 문다 — 카운트 적용과 전표 삭제가 한
 * 트랜잭션이라, 삭제가 죽으면 <b>이미 바뀐 카운트까지</b> 함께 되돌아간다.
 *
 * <p><b>이 성질이 이 축의 재시도 안전성 그 자체다.</b> 리뷰 축은 {@code calculatedAt} 상한이
 * 만드는 멱등성 위에 서 있지만, 여기는 멱등이 아니라 <b>일회</b>다 — 전표를 지우므로 같은 회차를
 * 두 번 돌리면 더할 것이 없다. 그래서 "실패한 시도가 통째로 롤백된다"가 깨지는 순간
 * 재시도가 곧 이중 반영(카운트만 남고 전표도 남는다)이나 유실(전표만 지워진다)이 된다.
 * {@code PlaceStatsProperties#bookmarkDeltaMaxAttempts}·{@code BookmarkCountDeltaProcessor}의
 * javadoc이 그 근거를 적어 두고 있는데, 지금까지 그것을 <b>실패시켜 확인한 테스트가 없었다</b>.
 *
 * <p><b>{@code @DataJpaTest}가 아니라 {@code @SpringBootTest}인 이유가 이 IT의 핵심이다.</b>
 * 슬라이스의 테스트 트랜잭션 안에서 돌리면 프로세서가 그 트랜잭션에 <em>참여</em>해 버려
 * 자기 경계를 열지 않는다. 그러면 롤백이 일어나는 시점이 테스트 종료 뒤로 밀려, 위 명제를
 * 검증하는 것이 아니라 테스트 하네스의 정리 동작을 보게 된다. 커밋되는 컨텍스트에서 돌려야
 * 프로세서의 {@code @Transactional}이 실제 경계가 되고, 롤백도 그 자리에서 일어난다.
 *
 * <p><b>실패를 어디에 심는지도 계약의 일부다.</b> 목으로 회차 전체를 흉내 내면 아무것도 증명하지
 * 못하므로, 읽기({@code findAllForConsume})와 카운트 UPDATE는 실물 그대로 돌리고 <b>마지막 삭제
 * 한 문장만</b> 스파이로 터뜨린다. 그 자리가 "카운트는 이미 DB에서 바뀌었고 전표는 아직 남아 있는"
 * 유일한 지점이다.
 */
@SpringBootTest
class BookmarkDeltaAtomicRollbackIT extends MySqlContainerSupport {

    /** 픽스처의 기준 시각. 전표의 {@code created_at}을 이 시각보다 앞에 둔다. */
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 9, 12, 1, 15, 0);

    /** 회차 전 카운트. 델타 +2가 얹히면 12가 되고, 롤백되면 이 값으로 돌아온다. */
    private static final int BASE_BOOKMARK_COUNT = 10;

    /**
     * 메서드 이름은 베이스의 {@code datasource}와 반드시 달라야 한다({@code @DynamicPropertySource}는
     * static이라 동명이면 상위를 <em>숨긴다</em>).
     *
     * <p>스케줄을 모두 끄는 이유는 다른 IT와 같다 — 스위트가 매시 :15를 지나면 스케줄러가 델타
     * 회차를 돌려 아래의 전표·카운트 단언이 흔들린다. 이 IT는 그 회차를 직접 부르므로 특히
     * {@code bookmark-delta-cron}이 빠지면 안 된다. {@code "-"}는 스프링이 "등록하지 않음"으로
     * 읽는 센티널이다({@code Scheduled.CRON_DISABLED}).
     */
    @DynamicPropertySource
    static void rollbackProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    @Autowired private BookmarkCountDeltaProcessor deltaProcessor;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 실패를 심는 자리. <b>이 빈만 스파이이고 나머지는 전부 실물이다</b> — 전표를 잠그고 읽는
     * 문장도, 카운트를 더하는 UPDATE도 실제로 MySQL에 나간다.
     */
    @SpyBean private BookmarkCountEventRepository countEventRepository;

    private long placeId;

    @BeforeEach
    void setUp() {
        placeId = jdbcTemplate.queryForObject(
                "SELECT id FROM places WHERE active = true ORDER BY id LIMIT 1", Long.class);
        // 행이 없으면 델타가 적용될 곳이 없어 이 IT가 아무것도 보지 못한다. 앞선 IT가
        // place_stats를 비우고 갔을 수 있으므로 회차마다 원본에서 다시 세운다.
        // 이 문장도 아웃박스를 비우므로 반드시 전표를 심기 전에 부른다.
        batchProcessor.rebuildRowsFromSource(CALCULATED_AT);
        jdbcTemplate.update(
                "UPDATE place_stats SET bookmark_count = ? WHERE place_id = ?",
                BASE_BOOKMARK_COUNT, placeId);
    }

    /**
     * <b>전표 삭제가 죽으면 이미 더해진 카운트도 함께 사라진다.</b>
     *
     * <p>실패 시점에 DB 카운트가 <em>이미 바뀌어 있었다</em>는 것을 같은 트랜잭션 안에서 읽어
     * 증거로 남긴다. 이 한 줄이 없으면 "UPDATE가 애초에 나가지 않아서 카운트가 그대로인" 경우와
     * 구분되지 않아, 프로세서가 아무 일도 하지 않아도 초록불이 켜진다.
     *
     * <p>그 in-transaction 읽기가 성립하는 근거는 프로덕션 계약과 같다 — {@code JdbcTemplate}은
     * 호출한 쪽의 스프링 트랜잭션에 그대로 참여한다({@code PlaceStatsJdbcRepository}). 카운트
     * UPDATE 자체가 그 성질 위에 서 있으므로, 이 읽기가 커밋 전 값을 못 본다면 원자성 계약도
     * 이미 깨져 있다는 뜻이다.
     */
    @Test
    void 전표_삭제가_실패하면_이미_더한_카운트까지_함께_롤백된다() {
        insertEvent(BookmarkTargetType.PLACE, placeId, 1);
        insertEvent(BookmarkTargetType.PLACE, placeId, 1);
        AtomicInteger countAtFailure = new AtomicInteger(-1);
        failDeletionAfterRecording(countAtFailure);

        assertThatThrownBy(() -> deltaProcessor.consumeAndApply())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countAtFailure.get())
                .as("삭제가 죽은 시점에 카운트는 이미 DB에서 바뀌어 있어야 한다")
                .isEqualTo(BASE_BOOKMARK_COUNT + 2);
        assertThat(bookmarkCountOf(placeId))
                .as("커밋되지 않았으므로 회차 전 값으로 돌아온다")
                .isEqualTo(BASE_BOOKMARK_COUNT);
        assertThat(remainingEventIds())
                .as("전표는 소비되지 않고 그대로 남는다")
                .hasSize(2);
    }

    /**
     * 롤백의 값어치는 <b>일감이 그대로 남는다</b>는 데 있다. 죽은 회차가 전표를 남겼으므로 다음
     * 회차(또는 같은 회차의 다음 시도)가 그 전표를 <b>정확히 한 번</b> 소비한다.
     *
     * <p>두 방향을 한 무대에서 본다. 카운트가 12면 이중 반영이 없는 것이고(롤백이 반쯤 남았다면
     * 14가 된다), 전표가 0장이면 유실이 없는 것이다(앞 회차가 전표만 지웠다면 여기서 0장을 읽어
     * 카운트가 10에 머문다).
     *
     * <p>{@code reset}으로 스텁만 걷어내는 이유는 {@code @SpyBean}의 기본 응답이 실물 위임이라
     * 초기화하면 실제 삭제 문장이 되살아나기 때문이다. 실패를 두 번째 호출에서 조건부로 건너뛰는
     * 방식은 쓸 수 없다 — 스프링 데이터 리포지토리는 인터페이스 프록시라 스파이 안에서
     * "진짜 메서드"를 부를 수 없다.
     */
    @Test
    void 롤백된_회차의_전표는_다음_회차가_정확히_한_번_소비한다() {
        insertEvent(BookmarkTargetType.PLACE, placeId, 1);
        insertEvent(BookmarkTargetType.PLACE, placeId, 1);
        failDeletionAfterRecording(new AtomicInteger(-1));
        assertThatThrownBy(() -> deltaProcessor.consumeAndApply())
                .isInstanceOf(DataIntegrityViolationException.class);

        reset(spiedEventRepository());
        DeltaResult retried = deltaProcessor.consumeAndApply();

        assertThat(retried.consumedEvents()).isEqualTo(2);
        assertThat(retried.updatedPlaces()).isEqualTo(1);
        assertThat(bookmarkCountOf(placeId))
                .as("앞 회차가 롤백됐으므로 델타는 정확히 한 번만 얹힌다")
                .isEqualTo(BASE_BOOKMARK_COUNT + 2);
        assertThat(remainingEventIds()).isEmpty();
    }

    // === 픽스처 ===

    /**
     * 회차의 마지막 문장인 전표 삭제를 터뜨린다. 던지기 <b>직전</b>에 같은 트랜잭션에서 카운트를
     * 읽어 두는 것이 이 헬퍼의 요점이다 — 롤백 뒤에는 그 중간 상태를 어디서도 볼 수 없다.
     */
    private void failDeletionAfterRecording(AtomicInteger countAtFailure) {
        willAnswer(invocation -> {
            countAtFailure.set(bookmarkCountOf(placeId));
            throw new DataIntegrityViolationException("전표 삭제가 죽은 회차");
        }).given(spiedEventRepository()).deleteAllByIdInBatch(any());
    }

    /**
     * 스프링 AOP가 스파이를 다시 프록시로 감싸면 주입된 참조가 목이 아니게 되어 스텁이
     * {@code NotAMockException}으로 죽는다. 감싸지 않은 경우 이 호출은 참조를 그대로 돌려준다
     * ({@code PlaceStatsSchedulerLockIT}의 같은 헬퍼와 같은 이유).
     */
    private BookmarkCountEventRepository spiedEventRepository() {
        return AopTestUtils.getUltimateTargetObject(countEventRepository);
    }

    private void insertEvent(BookmarkTargetType targetType, long targetId, int delta) {
        jdbcTemplate.update("""
                INSERT INTO bookmark_count_events (target_type, target_id, delta, created_at)
                VALUES (?, ?, ?, ?)
                """, targetType.name(), targetId, delta, CALCULATED_AT.minusMinutes(10));
    }

    private int bookmarkCountOf(long placeId) {
        return jdbcTemplate.queryForObject(
                "SELECT bookmark_count FROM place_stats WHERE place_id = ?", Integer.class, placeId);
    }

    private List<Long> remainingEventIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM bookmark_count_events ORDER BY id", Long.class);
    }

    /**
     * 이 IT는 롤백되지 않는다({@code @SpringBootTest}는 기본 커밋) — 그것이 검증의 전제이므로
     * 뒷정리를 스스로 진다. {@code place_stats}를 비우는 이유는 그 테이블이 비어 있음을 전제하는
     * 다른 IT가 있기 때문이고({@code PlaceStatsRepositoryIT}), 전표를 비우는 이유는 남은 전표가
     * 뒤 클래스의 델타 회차에 섞이기 때문이다.
     *
     * <p>{@code @AfterAll} + {@code DriverManager}인 이유는 static이라 {@code @Autowired}에
     * 닿을 수 없어서다.
     */
    @AfterAll
    static void cleanUpCommittedRows() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM bookmark_count_events");
            st.executeUpdate("DELETE FROM place_stats");
        }
    }
}
