package org.sopt.solply_server.domain.place.cache.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>번호를 올리는 UPDATE 한 문장이 실제 MySQL에서 어떻게 셈하는가.</b>
 *
 * <p>{@link SnapshotMetadata#isNewerThan}이 사전식인 근거가 이 문장이다 — 회차를 올리는 UPDATE가
 * <b>같은 문장에서</b> revision을 0으로 되돌리므로, revision은 "이 회차의 몇 번째 변경"이 된다.
 * 셈이 {@code IF(? = 1, ...)} 안에 숨어 있어 자바 단위 테스트로는 볼 수 없고, 리셋과 회차 증가가
 * 갈라지면 <b>둘이 어긋난 번호 쌍이 DB에 보이는 창</b>이 열리므로 실제 DB에서 값으로 못 박는다.
 *
 * <p>{@code bump}은 {@code MANDATORY}라 트랜잭션 안에서만 돈다 — 그래서 아래도 운영과 같이
 * 트랜잭션을 열고 부른다.
 */
@SpringBootTest
class SnapshotMetadataRepositoryIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void metadataRepositoryProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    @Autowired private SnapshotMetadataRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate bumpTransaction;

    @BeforeEach
    void givenBumpTransaction() {
        bumpTransaction = new TransactionTemplate(transactionManager);
        bumpTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * <b>회차를 올리면 revision은 0으로 돌아간다.</b> 이것이 깨져 revision이 이어지면 로그의
     * {@code (13, 41)}이 "13회차의 41번째"가 아니라 아무 뜻도 없는 누적 수가 된다.
     */
    @Test
    void 회차를_올리는_bump는_revision을_0으로_되돌린다() {
        bump(SnapshotCursorPolicy.PRESERVE);
        bump(SnapshotCursorPolicy.PRESERVE);
        SnapshotMetadata before = repository.read();
        assertThat(before.revision())
                .as("리셋을 관찰하려면 앞 회차의 revision이 0이 아니어야 한다").isPositive();

        bump(SnapshotCursorPolicy.ADVANCE);

        SnapshotMetadata after = repository.read();
        assertThat(after.cursorVersion()).isEqualTo(before.cursorVersion() + 1);
        assertThat(after.revision()).isZero();
        assertThat(after.isNewerThan(before))
                .as("리셋된 (n+1, 0)도 (n, r)보다 새것이다 — 여기가 깨지면 집계 회차마다"
                        + " 새 스냅샷이 버려진다").isTrue();
    }

    /**
     * <b>커서를 유지하는 회차는 revision만 올린다.</b> 표시값 하나 고치자고 회차를 올리면 진행
     * 중인 스크롤이 그 자리에서 만료된다 — 번호를 둘로 가른 값어치가 이 단언이다.
     */
    @Test
    void 커서를_유지하는_bump는_revision만_올린다() {
        SnapshotMetadata before = repository.read();

        bump(SnapshotCursorPolicy.PRESERVE);

        SnapshotMetadata after = repository.read();
        assertThat(after.cursorVersion()).isEqualTo(before.cursorVersion());
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
    }

    private void bump(SnapshotCursorPolicy policy) {
        bumpTransaction.executeWithoutResult(status -> repository.bump(policy));
    }

    /**
     * 기동 백필({@code ApplicationReadyEvent})이 남긴 place_stats 행을 지운다 — 뒤에 도는
     * {@code @DataJpaTest}({@code PlaceStatsRepositoryIT})가 빈 테이블을 전제한다.
     */
    @AfterAll
    static void cleanUpCommittedRows() throws Exception {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
        }
    }
}
