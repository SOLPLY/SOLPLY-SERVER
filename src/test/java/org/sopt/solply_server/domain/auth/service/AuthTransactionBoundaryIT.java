package org.sopt.solply_server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenPayload;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.support.AuthMySqlSupport;
import org.sopt.solply_server.support.MutableClock;
import org.sopt.solply_server.support.MutableClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회전의 트랜잭션 경계 — <b>바깥에 이미 트랜잭션이 열려 있을 때</b>를 문다.
 *
 * <p>요구사항이 "외부 서비스의 기존 트랜잭션에 조용히 참여해 격리 수준이 무시되지 않게 경계를
 * 분리한다"고 못 박은 자리다. 경쟁 IT는 바깥 트랜잭션이 <b>없을 때</b>를 보므로
 * {@code REQUIRES_NEW}를 {@code REQUIRED}로 바꿔도 전부 통과한다 — 참여는 바깥이 있을 때만
 * 일어나기 때문이다. 여기서 그 경우를 만든다.
 *
 * <p>애노테이션을 리플렉션으로 읽지 않는다. 읽어 봐야 "그렇게 적혀 있다"까지이고, 스프링이
 * 참여 시 격리 지정을 <b>조용히 버린다</b>는 사실({@code validateExistingTransaction} 기본 false)은
 * 실제로 열린 트랜잭션의 DB 격리를 재야 드러난다.
 */
@SpringBootTest
@Import(MutableClockConfig.class)
class AuthTransactionBoundaryIT extends AuthMySqlSupport {

    private static final String NICKNAME_PREFIX = "경계IT유저";
    private static final String OUTER_TX = "외부-트랜잭션";
    private static final String ROTATE_TX = RefreshTokenService.class.getName() + ".rotate";

    /** 관측한 트랜잭션 begin — {@code 이름 : DB 격리}. 이 두 값이 함께 있어야 판정이 된다. */
    private static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthProperties authProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.setTo(MutableClockConfig.START);
        userId = createUser();
        OBSERVED.clear();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    /**
     * <b>이 테스트가 E의 중심이다.</b> 바깥이 REPEATABLE READ로 열려 있는데도 회전은 자기
     * 트랜잭션을 새로 열고 그 트랜잭션의 DB 격리가 READ COMMITTED다.
     *
     * <p>단언 하나가 세 가지를 동시에 가른다 — 회전이 <b>begin했다</b>(참여가 아니다), 그
     * 트랜잭션이 <b>RC다</b>, 그리고 관측 장치가 <b>실제로 구분한다</b>(같은 장치가 바깥을 RR로
     * 읽었다). {@code REQUIRED}로 바꾸면 begin이 한 번뿐이라 목록에서 회전 항목이 사라진다.
     */
    @Test
    void 외부_REPEATABLE_READ_안에서도_회전은_자기_READ_COMMITTED_트랜잭션을_연다() {
        RefreshTokenPayload parent = payloadOf(login());

        RotationResult result = outerTx(TransactionDefinition.ISOLATION_REPEATABLE_READ, status -> {
            // 바깥 트랜잭션이 커넥션을 실제로 잡게 한다. 잠그지는 않는다 —
            // users 행을 잠그면 안쪽 회전이 자기 잠금을 기다리다 교착이 된다.
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class);
            return refreshTokenService.rotate(parent);
        });

        assertThat(result).isInstanceOf(RotationResult.Rotated.class);
        assertThat(OBSERVED).containsExactly(
                OUTER_TX + " : REPEATABLE-READ",
                ROTATE_TX + " : READ-COMMITTED");
    }

    /**
     * <b>바깥이 롤백돼도 회전은 남는다.</b> 경계가 분리돼 있다는 말의 관측 가능한 형태이고,
     * {@code REQUIRED}면 자식과 부모의 회전 표시가 함께 사라져 이 단언이 깨진다.
     */
    @Test
    void 외부_트랜잭션이_롤백돼도_회전은_커밋된_채_남는다() {
        TokenCollectionDto issued = login();
        RefreshTokenPayload parent = payloadOf(issued);

        outerTx(TransactionDefinition.ISOLATION_DEFAULT, status -> {
            RotationResult result = refreshTokenService.rotate(parent);
            status.setRollbackOnly();
            return result;
        });

        assertThat(committedRow(parent.jwtId()).get("rotated_at")).isNotNull();
        assertThat(committedRows("SELECT * FROM refresh_token WHERE parent_jwt_id = ?",
                parent.jwtId())).hasSize(1);
    }

    /**
     * <b>회전은 바깥이 끝나기 전에 이미 커밋돼 있다.</b> 아직 살아 있는 바깥 트랜잭션과 무관하게
     * 제3의 커넥션이 자식을 본다 — 같은 트랜잭션·같은 커넥션이었다면 보일 수 없는 상태다.
     */
    @Test
    void 회전은_외부_트랜잭션이_끝나기_전에_이미_독립_커넥션에서_보인다() {
        RefreshTokenPayload parent = payloadOf(login());

        outerTx(TransactionDefinition.ISOLATION_DEFAULT, status -> {
            RotationResult result = refreshTokenService.rotate(parent);
            String childJwtId = payloadOf(tokensOf(result)).jwtId();

            assertThat(committedRow(childJwtId).get("revoked_at")).isNull();
            assertThat(committedRow(parent.jwtId()).get("rotated_at")).isNotNull();
            return result;
        });
    }

    /**
     * <b>재사용 폐기가 바깥 롤백을 타면 안 된다.</b> 폐기를 값으로 돌려주는 설계가 막으려던 실패가
     * 정확히 "폐기는 롤백되고 401만 나간다"이고({@code RotationResult} javadoc), 호출자가 트랜잭션을
     * 열고 있다가 뒤에서 롤백하면 그 실패가 그대로 재현된다 — 탈취범의 토큰이 살아 있는 채로
     * 경고만 나가는 상태다. 경계가 분리돼 있어야 이 시나리오에서도 폐기가 남는다.
     */
    @Test
    void 재사용_폐기는_외부_트랜잭션의_롤백으로_되돌아가지_않는다() {
        TokenCollectionDto familyA = login();
        TokenCollectionDto familyB = login();
        refreshTokenService.rotate(payloadOf(familyA));
        clock.advance(authProperties.getRotationGrace());

        RotationResult result = outerTx(TransactionDefinition.ISOLATION_DEFAULT, status -> {
            RotationResult reuse = refreshTokenService.rotate(payloadOf(familyA));
            status.setRollbackOnly();
            return reuse;
        });

        assertThat(result).isInstanceOf(RotationResult.ReuseDetected.class);
        assertThat(committedRows("SELECT * FROM refresh_token WHERE user_id = ?", userId))
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
        // 손대지 않은 다른 계열까지 끊긴 것이 커밋됐다
        assertThat(committedRow(payloadOf(familyB).jwtId()).get("revoked_at")).isNotNull();
    }

    // === 관측 ===

    /**
     * 격리 수준은 트랜잭션이 끝나면 커넥션에서 원복되므로 사후에 볼 수 없다.
     * {@code TransactionExecutionListener.afterBegin}만이 "begin됐고 아직 아무 일도 안 한" 시점에
     * 끼어든다. 리스너 빈은 자동으로 주워 가지 않으므로 트랜잭션 매니저에 직접 붙인다.
     *
     * <p>회전이 {@code JdbcTemplate}을 쓰므로 격리도 그 길로 읽는다. 트랜잭션에 커넥션이 묶여
     * 있지 않으면 풀에서 새 커넥션을 받아 엉뚱한 값을 읽게 되므로, 그 경우는 값을 만들지 않고
     * {@code UNBOUND}로 남겨 단언이 조용히 통과하지 못하게 한다.
     */
    @TestConfiguration
    static class TxProbeConfig {

        TxProbeConfig(PlatformTransactionManager txManager, DataSource dataSource,
                JdbcTemplate jdbcTemplate) {
            ((AbstractPlatformTransactionManager) txManager).addListener(
                    new TransactionExecutionListener() {
                        @Override
                        public void afterBegin(TransactionExecution tx, Throwable beginFailure) {
                            String name = tx.getTransactionName();
                            if (!OUTER_TX.equals(name) && !ROTATE_TX.equals(name)) {
                                return;
                            }
                            String isolation =
                                    TransactionSynchronizationManager.hasResource(dataSource)
                                            ? jdbcTemplate.queryForObject(
                                                    "SELECT @@transaction_isolation", String.class)
                                            : "UNBOUND";
                            OBSERVED.add(name + " : " + isolation);
                        }
                    });
        }
    }

    // === helpers ===

    private <T> T outerTx(int isolationLevel, TransactionCallback<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setName(OUTER_TX);
        template.setIsolationLevel(isolationLevel);
        return template.execute(work);
    }

    private TokenCollectionDto login() {
        return refreshTokenService.issue(userId, SocialPlatform.KAKAO);
    }

    private static TokenCollectionDto tokensOf(RotationResult result) {
        return ((RotationResult.Rotated) result).tokens();
    }

    private RefreshTokenPayload payloadOf(TokenCollectionDto tokens) {
        return jwtTokenProvider.parseRefreshToken(tokens.refreshToken());
    }

    private Map<String, Object> committedRow(String jwtId) {
        List<Map<String, Object>> rows =
                committedRows("SELECT * FROM refresh_token WHERE jwt_id = ?", jwtId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> committedRows(String sql, Object... args) {
        return readCommittedRows(sql, args);
    }

    private long createUser() {
        // users.nickname은 VARCHAR(30)이다 — UUID를 통째로 붙이면 42자가 되어 INSERT가 죽는다
        String nickname = NICKNAME_PREFIX
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }
}
