package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.BDDMockito.willThrow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.bookmark.entity.BookmarkTargetType;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadata;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataService;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor.DeltaResult;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;

/**
 * 델타 소비 회차의 <b>트랜잭션 경계</b>를 실제 MySQL로 못 박는다 — 표식·적용·삭제·번호 갱신이
 * 함께 커밋되고 함께 롤백되는가, 그리고 롤백된 전표를 다음 호출이 다시 삼키는가.
 *
 * <p><b>영속 회차 등록을 걷어낸 뒤(2026-09-13) 이 경계가 유일한 안전장치다.</b> 요청 행도
 * 워터마크도 없으므로 "실패한 회차를 다시 돌린다"를 떠받치는 것은 <b>실패한 시도가 아무 흔적도
 * 남기지 않는다</b>는 성질 하나이고, 그 성질이 깨지면 전표가 적용 없이 사라지거나 두 번 적용된다.
 *
 * <p><b>{@code @DataJpaTest}가 아니라 {@code @SpringBootTest}인 것이 이 파일의 전제다.</b>
 * 슬라이스 테스트는 자기 트랜잭션으로 감싸고 끝에 롤백하므로 프로세서가 그 트랜잭션에 <b>참여</b>해
 * 버린다 — 그러면 커밋도 롤백도 테스트가 정한 시점에 일어나고, 여기서 보려는 경계가 사라진다.
 * 값 계약(접기·바닥·멱등성)은 {@code BookmarkCountDeltaProcessorIT}가 슬라이스로 본다.
 *
 * <p><b>실패를 마지막 문장에 주입하는 것이 단언의 핵심이다.</b> 번호 갱신이 커밋 뒤 훅이라면
 * 그 실패로 앞의 세 문장이 되돌아가지 않는다 — 되돌아간다는 사실이 곧 "네 단계가 한 트랜잭션"의
 * 증거다.
 *
 * <p><b>이 파일은 동시 실행을 보지 않는다.</b> 여기서 확인하는 것은 <b>순차</b> 호출의 경계이고,
 * 그것으로 겹쳐 도는 두 회차의 안전성을 주장할 수는 없다 ({@code PlaceStatsFacade} javadoc의
 * 임대 만료 문단).
 */
@SpringBootTest
class PlaceStatsRoundAtomicityIT extends MySqlContainerSupport {

    /**
     * 네 회차를 모두 꺼 둔다. 켜 두면 배경 스케줄러가 심어 둔 전표를 먼저 삼켜, 이 IT가 보는 것이
     * "내가 부른 회차가 무엇을 했나"가 아니라 스케줄러와의 경주 결과가 된다.
     */
    @DynamicPropertySource
    static void atomicityProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    @Autowired private BookmarkCountDeltaProcessor deltaProcessor;
    @Autowired private PlaceStatsBatchProcessor batchProcessor;
    @Autowired private SnapshotMetadataRepository metadataRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 마지막 문장을 실패시키는 지점. <b>스파이여야 한다</b> — 목으로 두면 정상 경로의 번호 갱신까지
     * 사라져, "커밋되면 번호가 오른다"는 짝 단언이 스텁을 되읽는 동어반복이 된다.
     */
    @SpyBean private SnapshotMetadataService metadataService;

    private long placeId;

    @BeforeEach
    void setUp() {
        placeId = jdbcTemplate.queryForObject(
                "SELECT id FROM places WHERE active = true ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update("DELETE FROM bookmark_count_events");
        // 카운트 행을 원본에서 세워 둔다. 이 문장도 아웃박스를 비우므로 전표를 심는 것보다
        // 반드시 먼저 와야 한다 — 뒤집으면 심어 둔 전표가 조용히 사라진다.
        batchProcessor.rebuildRowsFromSource(java.time.LocalDateTime.now());
        willCallRealMethod().given(spiedMetadataService()).markChanged(any());
    }

    /**
     * 정상 경로 — <b>넷이 함께 커밋된다.</b> 롤백 테스트의 대조군이고, 이것이 없으면 아래 단언이
     * "애초에 아무 일도 하지 않는다"와 구분되지 않는다.
     */
    @Test
    @DisplayName("표식·적용·삭제·번호 갱신이 함께 커밋된다")
    void 커밋되면_카운트와_번호가_함께_오르고_전표는_사라진다() {
        int before = bookmarkCount();
        SnapshotMetadata metadataBefore = metadataRepository.read();
        insertEvent(3);
        insertEvent(2);

        DeltaResult result = deltaProcessor.consumeAndApply();

        assertThat(result.consumedEvents()).isEqualTo(2);
        assertThat(bookmarkCount()).isEqualTo(before + 5);
        assertThat(remainingEvents()).isZero();
        SnapshotMetadata metadataAfter = metadataRepository.read();
        assertThat(metadataAfter.revision())
                .as("집계가 값을 고쳤으면 리비전이 오른다")
                .isGreaterThan(metadataBefore.revision());
        assertThat(metadataAfter.cursorVersion())
                .as("북마크 수는 인기순의 정렬 키라 커서 회차까지 오른다"
                        + " — 15분마다 스크롤이 만료될 수 있는 이유가 이것이다")
                .isGreaterThan(metadataBefore.cursorVersion());
    }

    /**
     * <b>마지막 문장이 던지면 앞의 셋도 없던 일이 된다.</b> 표식이 커밋된 채 남으면 그 전표는
     * 적용도 삭제도 되지 않은 상태로 영영 남고, 그것을 회수하는 장치는 어디에도 없다.
     *
     * <p>번호가 그대로인 것도 같은 무게의 단언이다 — 올랐다면 "값이 바뀌지 않았는데 전 인스턴스가
     * 원본을 다시 읽는" 헛일이 된다.
     */
    @Test
    @DisplayName("번호 갱신이 실패하면 표식·적용·삭제가 전부 롤백된다")
    void 마지막_문장이_던지면_회차가_통째로_되돌아간다() {
        int before = bookmarkCount();
        SnapshotMetadata metadataBefore = metadataRepository.read();
        insertEvent(7);
        willThrow(new IllegalStateException("번호 갱신 실패"))
                .given(spiedMetadataService()).markChanged(any(SnapshotCursorPolicy.class));

        assertThatThrownBy(() -> deltaProcessor.consumeAndApply())
                .isInstanceOf(IllegalStateException.class);

        assertThat(bookmarkCount())
                .as("적용이 되돌아간다")
                .isEqualTo(before);
        assertThat(remainingEvents())
                .as("삭제가 되돌아가 전표가 그대로 남는다")
                .isEqualTo(1);
        assertThat(claimedEvents())
                .as("표식도 되돌아간다 — 커밋된 채 남은 표식을 회수하는 장치는 없다")
                .isZero();
        SnapshotMetadata metadataAfter = metadataRepository.read();
        assertThat(metadataAfter.revision()).isEqualTo(metadataBefore.revision());
        assertThat(metadataAfter.cursorVersion()).isEqualTo(metadataBefore.cursorVersion());
    }

    /**
     * <b>실패한 회차를 메우는 것은 다음 호출 하나다.</b> 요청 행도 복구 폴도 없으므로, 롤백된 전표가
     * 다음 발화에 그대로 잡히는 이 성질이 재시도 계약의 전부다.
     *
     * <p>여기서 부르는 것이 파사드가 아니라 프로세서인 이유: 파사드 메서드에는
     * {@code lockAtLeastFor PT1M}이 걸려 있어 1분 안의 두 번째 호출은 락에 막혀 건너뛰어진다.
     * 그 건너뜀은 {@code PlaceStatsSchedulerLockIT}가 보는 별개의 계약이다.
     */
    @Test
    @DisplayName("롤백된 전표는 다음 호출이 그대로 다시 삼킨다")
    void 실패한_회차의_전표를_다음_호출이_다시_삼킨다() {
        int before = bookmarkCount();
        insertEvent(4);
        willThrow(new IllegalStateException("번호 갱신 실패"))
                .given(spiedMetadataService()).markChanged(any(SnapshotCursorPolicy.class));
        assertThatThrownBy(() -> deltaProcessor.consumeAndApply())
                .isInstanceOf(IllegalStateException.class);

        willCallRealMethod().given(spiedMetadataService()).markChanged(any());
        DeltaResult retried = deltaProcessor.consumeAndApply();

        assertThat(retried.consumedEvents())
                .as("첫 시도가 표식을 남기지 않았으므로 같은 전표가 다시 잡힌다")
                .isEqualTo(1);
        assertThat(bookmarkCount())
                .as("두 번 적용되지 않는다 — 첫 시도의 적용은 롤백됐다")
                .isEqualTo(before + 4);
        assertThat(remainingEvents()).isZero();
    }

    /**
     * 스프링 AOP가 스파이를 다시 프록시로 감싸면 주입된 참조는 목이 아니게 되어 스터빙이
     * {@code NotAMockException}으로 죽는다. 감싸지 않은 경우 이 호출은 참조를 그대로 돌려준다.
     */
    private SnapshotMetadataService spiedMetadataService() {
        return AopTestUtils.getUltimateTargetObject(metadataService);
    }

    private void insertEvent(int delta) {
        jdbcTemplate.update("""
                INSERT INTO bookmark_count_events (target_type, target_id, delta, created_at)
                VALUES (?, ?, ?, NOW(3))
                """, BookmarkTargetType.PLACE.name(), placeId, delta);
    }

    private int bookmarkCount() {
        return jdbcTemplate.queryForObject(
                "SELECT bookmark_count FROM place_stats WHERE place_id = ?", Integer.class, placeId);
    }

    private long remainingEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookmark_count_events", Long.class);
    }

    /** 표식이 찍힌 채 커밋돼 남은 전표. 정상 상태에서는 언제나 0이어야 한다 */
    private long claimedEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookmark_count_events WHERE consumption_id IS NOT NULL",
                Long.class);
    }

    /**
     * 이 IT는 롤백되지 않는다({@code @SpringBootTest}는 기본 커밋). 배치가 <b>모든 장소</b>에
     * place_stats 행을 남기므로 지우지 않으면 place_stats가 비어 있음을 전제하는 다른 IT가 깨진다
     * ({@code PlaceStatsRepositoryIT}) — {@code PlaceStatsSchedulerLockIT}가 같은 이유로 같은
     * 정리를 한다.
     */
    @AfterAll
    static void cleanUpCommittedRows() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM bookmark_count_events");
        }
    }
}
