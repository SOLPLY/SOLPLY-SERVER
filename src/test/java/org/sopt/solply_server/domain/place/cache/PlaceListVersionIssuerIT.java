package org.sopt.solply_server.domain.place.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회차 버전이 <b>DB 발급 테이블의 번호</b>라는 것을 실제 DB 위에서 못 박는다. 겨누는 것은 넷이다 —
 * 번호가 단조인가, 읽기 전용 재빌드 트랜잭션 안에서도 발급이 되고 곧바로 커밋되는가, 사진에 붙는
 * 번호가 정말 방금 발급된 그 번호인가, 발급이 실패했을 때 직전 사진이 남는가.
 *
 * <p><b>왜 IT인가.</b> 이 기능의 값이 전부 DB 쪽에 있다. AUTO_INCREMENT가 단조를 만들고,
 * {@code REQUIRES_NEW}가 읽기 전용 트랜잭션 안의 INSERT를 가능하게 하며, 그 트랜잭션이 즉시
 * 커밋되어야 나중에 빌더가 여럿이 됐을 때 둘이 같은 번호를 받지 않는다. 목으로는 셋 중 하나도
 * 확인되지 않는다.
 *
 * <p><b>{@code @SpyBean}인 이유.</b> 앞의 셋은 진짜 발급이 돌아야 하고 마지막 하나는 발급이
 * 실패해야 한다. 스파이는 기본이 진짜 동작이라 한 컨텍스트에서 둘 다 세울 수 있다 — 발급 실패를
 * DB 쪽에서 만들려면 테이블을 지워야 하는데, 컨테이너와 스키마를 다른 IT와 공유하므로 그럴 수 없다.
 */
@SpringBootTest
class PlaceListVersionIssuerIT extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void versionIssuerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
    }

    @SpyBean private PlaceListVersionIssuer issuer;
    @Autowired private PlaceListSnapshotLoader loader;
    @Autowired private PlaceListSnapshot snapshot;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * <b>연속 발급은 반드시 커진다.</b> 홀더의 단조 가드({@code PlaceListSnapshot#adopt})가 이
     * 성질 위에 서 있어서, 여기가 무너지면 새로 지은 사진이 "낡은 버전"으로 조용히 거절된다.
     */
    @Test
    void 연속_발급은_증가한다() {
        long first = issuer.issue();
        long second = issuer.issue();
        long third = issuer.issue();

        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);
    }

    /**
     * <b>읽기 전용 트랜잭션 안에서도 발급이 되고, 그 자리에서 커밋된다.</b> 재빌드가
     * {@code readOnly = true}라 발급이 그 트랜잭션에 얹히면 INSERT가 거부되거나, 통과하더라도
     * 재빌드가 끝날 때까지 번호가 남에게 안 보인다.
     *
     * <p>확인은 <b>스프링이 모르는 별도 커넥션</b>으로 한다. 같은 트랜잭션의 커넥션으로 읽으면
     * 자기가 쓴 것을 보는 것과 구분되지 않아 아무것도 증명하지 못한다.
     */
    @Test
    void 읽기_전용_트랜잭션_안에서도_발급이_즉시_커밋된다() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        Long issued = readOnly.execute(status -> {
            long version = issuer.issue();
            assertThat(existsOnOwnConnection(version))
                    .as("바깥 커넥션에서 이미 보인다 = 발급 트랜잭션이 커밋됐다")
                    .isTrue();
            return version;
        });

        assertThat(issued).isNotNull();
    }

    /**
     * <b>사진에 붙는 번호가 곧 방금 발급된 번호다.</b> 재빌드가 번호를 받아 놓고 다른 값을 사진에
     * 붙이면 커서가 가리키는 회차와 실제 회차가 갈린다 — 발급 테이블의 최댓값과 대조해 못 박는다.
     */
    @Test
    void 재빌드는_방금_발급받은_번호를_사진_버전으로_쓴다() {
        long before = lastIssuedVersion();

        loader.rebuild();
        long first = snapshot.current().version();

        loader.rebuild();
        long second = snapshot.current().version();

        assertThat(first).as("직전 발급보다 크다").isGreaterThan(before);
        assertThat(first).as("첫 회차의 번호가 그때 발급된 것이다").isLessThan(second);
        assertThat(second).as("발급 테이블의 최신 번호와 같다").isEqualTo(lastIssuedVersion());
    }

    /**
     * <b>발급이 실패하면 사진은 직전 회차 그대로다.</b> 재빌드 실패의 기존 정책이 그대로 적용되는
     * 자리다 — 여기서 밀리초 시각 같은 폴백을 두면 번호 공간이 둘로 섞여, 한 번의 폴백이 그 뒤
     * 실제 발급 번호를 전부 "낡은 버전"으로 만든다.
     */
    @Test
    void 발급이_실패하면_직전_사진이_남는다() {
        loader.rebuild();
        PlaceListPhoto held = snapshot.current();
        willThrow(new IllegalStateException("발급 실패")).given(issuer).issue();

        assertThatThrownBy(loader::rebuild).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot.current()).as("사진이 교체되지 않았다").isSameAs(held);
    }

    private long lastIssuedVersion() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM place_list_snapshot_versions", Long.class);
        return max == null ? 0L : max;
    }

    /** 컨테이너에 직접 연 커넥션 — 진행 중인 스프링 트랜잭션과 아무 관계가 없다 */
    private boolean existsOnOwnConnection(long version) {
        try (Connection connection = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM place_list_snapshot_versions WHERE id = " + version)) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
