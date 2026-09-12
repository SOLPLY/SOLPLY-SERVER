package org.sopt.solply_server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenPayload;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.support.AuthMySqlSupport;
import org.sopt.solply_server.support.MutableClock;
import org.sopt.solply_server.support.MutableClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회전 경쟁의 <b>실제 재현</b> — 독립 커넥션 · 독립 트랜잭션 · barrier.
 *
 * <h2>왜 실제 MySQL이어야 하는가</h2>
 * 이 클래스가 증명하는 것은 전부 잠금과 커밋의 성질이다. H2로는 볼 수 없다 —
 * {@code SELECT ... FOR UPDATE}의 행 잠금 아래에서 두 트랜잭션이 어떻게 직렬화되는지,
 * {@code ux_refresh_token_parent_jwt_id}가 두 갈래를 어떻게 막는지, 조건부 UPDATE가
 * 0행을 내는 순간이 실제로 존재하는지가 전부 엔진의 동작이다.
 *
 * <h2>왜 barrier를 걸어야 하는가 — 동시에 쏘는 것만으로는 부족하다</h2>
 * 정상 경로는 사용자 잠금으로 직렬화된다. 그래서 두 요청을 그냥 동시에 쏘면 <b>1행 경로가 두 번</b>
 * 돌 뿐이고(뒤에 온 쪽이 잠금을 기다렸다가 회전된 부모를 다시 읽는다), 검증하려는 CAS 0행 분기는
 * 아예 실행되지 않는다. 0행을 보려면 <b>두 커넥션이 각자의 분기용 SELECT를 먼저 끝낸 뒤</b>
 * 잠금 획득이 어긋나야 한다. 그 지점이 {@code lockUser}이고, 스파이가 거기서 두 스레드를 만나게 한다.
 *
 * <p><b>프로덕션 코드에는 아무 장치도 없다.</b> 테스트용 우회 API도 latch도 넣지 않았다 —
 * 스파이는 스프링 컨텍스트의 배선일 뿐이고, 지연이 걸리는 자리는 저장소 메서드의 바깥이다.
 *
 * <h2>왜 커밋을 다른 커넥션으로 읽는가</h2>
 * "폐기됐다"와 "폐기가 커밋됐다"는 다른 말이다. 재사용 감지를 값으로 돌려주는 설계가 막으려던
 * 실패가 정확히 "폐기가 롤백되고 401만 나간다"이므로, 테스트도 그 실패를 재현할 수 있는 모양,
 * 곧 <b>트랜잭션 밖에서 읽는 모양</b>이어야 한다.
 */
@SpringBootTest
@Import(MutableClockConfig.class)
class RefreshTokenRotationConcurrencyIT extends AuthMySqlSupport {

    private static final String NICKNAME_PREFIX = "경쟁IT유저";
    private static final long AWAIT_SECONDS = 20L;

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private AuthService authService;
    @Autowired private org.sopt.solply_server.domain.auth.config.AuthProperties authProperties;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * 경쟁의 만남 지점을 만들기 위한 스파이. <b>동작을 바꾸지 않는다</b> — 실제 메서드를 그대로
     * 부르되 그 앞에서 스레드를 세운다.
     */
    @SpyBean private RefreshTokenRepository refreshTokenRepository;

    private ExecutorService executor;
    private long userId;

    @BeforeEach
    void setUp() {
        clock.setTo(MutableClockConfig.START);
        userId = createUser("주");
        executor = Executors.newFixedThreadPool(2, new NamedThreadFactory());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE nickname LIKE ?", NICKNAME_PREFIX + "%");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 1·2 — 동시 회전
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>이 스위트의 중심 테스트다.</b> 같은 부모로 동시에 들어온 두 요청에서
     *
     * <ul>
     *   <li>조건부 UPDATE가 <b>1행과 0행으로 갈리고</b>(CAS의 승패가 실재한다),</li>
     *   <li>자식은 <b>정확히 하나</b>이며,</li>
     *   <li>진 쪽이 받아 가는 refresh가 이긴 쪽의 것과 <b>바이트까지 같고</b>,</li>
     *   <li><b>정상 경쟁에서는 전체 폐기가 일어나지 않는다.</b></li>
     * </ul>
     *
     * <p>마지막 항목이 특히 중요하다 — 경쟁을 재사용으로 오독하면 멀쩡한 사용자가 계정 전체를
     * 잃는다. 유예가 있는 이유가 그것이고, 이 테스트가 그 이유를 값으로 만든다.
     */
    @Test
    void 동시_회전은_자식_하나를_만들고_진_쪽도_같은_refresh를_받는다() throws Exception {
        TokenCollectionDto issued = login();
        RefreshTokenPayload parent = payloadOf(issued.refreshToken());

        CyclicBarrier bothReadParent = new CyclicBarrier(2);
        List<Integer> casResults = new CopyOnWriteArrayList<>();
        gateLockUserForRacers(bothReadParent);
        recordCasResults(casResults);

        List<RotationResult> results = runConcurrently(
                () -> refreshTokenService.rotate(parent),
                () -> refreshTokenService.rotate(parent));

        // 1) 조건부 UPDATE가 실제로 갈렸다 — 0행 경로가 코드에만 있는 분기가 아니다
        assertThat(casResults).containsExactlyInAnyOrder(1, 0);

        // 2) 둘 다 정상 응답이고, 받은 refresh는 바이트까지 같다
        assertThat(results).allMatch(RotationResult.Rotated.class::isInstance);
        List<String> refreshTokens = results.stream()
                .map(r -> ((RotationResult.Rotated) r).tokens().refreshToken())
                .toList();
        assertThat(refreshTokens.get(0)).isEqualTo(refreshTokens.get(1));

        // 3) 자식은 하나뿐이고, 그 자식이 둘 다 받아 간 그 토큰이다
        List<Map<String, Object>> children = committedRows(
                "SELECT * FROM refresh_token WHERE parent_jwt_id = ?", parent.jwtId());
        assertThat(children).hasSize(1);
        assertThat(children.getFirst().get("jwt_id"))
                .isEqualTo(payloadOf(refreshTokens.getFirst()).jwtId());

        // 4) 정상 경쟁에서는 아무것도 폐기되지 않았다
        assertThat(committedRowsOfUser())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNull());
    }

    /**
     * <b>진 쪽은 최신 행을 다시 읽는다.</b> 영속성 컨텍스트나 REPEATABLE READ에 갇히면 CAS에 진
     * 요청이 <em>자기가 처음 본 값</em>(ACTIVE)을 다시 보고, 그 값으로 재분류하면 멀쩡한 경쟁이
     * 정합성 오류(ACTIVE인데 CAS가 실패했다)로 읽힌다.
     *
     * <p>재조회가 실제로 일어났다는 증거가 {@code findByJwtId} 호출 3회다 — 두 스레드의 분기용
     * 조회 둘에, 진 쪽의 재조회 하나.
     */
    @Test
    void CAS에_진_요청은_최신_부모를_다시_읽는다() throws Exception {
        TokenCollectionDto issued = login();
        RefreshTokenPayload parent = payloadOf(issued.refreshToken());
        gateLockUserForRacers(new CyclicBarrier(2));

        runConcurrently(
                () -> refreshTokenService.rotate(parent),
                () -> refreshTokenService.rotate(parent));

        org.mockito.Mockito.verify(spiedRepository(), org.mockito.Mockito.times(3))
                .findByJwtId(parent.jwtId());
        // 그리고 그 재조회가 본 것은 GRACE 상태의 부모다 — 자식을 찾아 문자열을 되살렸다
        org.mockito.Mockito.verify(spiedRepository()).findByParentJwtId(parent.jwtId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 8 — 자식 INSERT 실패 시 부모 롤백
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>실패를 주입하지 않고 실제 제약으로 만든다.</b> {@code ux_refresh_token_parent_jwt_id}는
     * "한 부모는 자식을 하나만 갖는다"를 DB에 박아 둔 것이고, 그 제약에 걸리는 상황을 그대로
     * 재현하면 자식 INSERT 실패가 실물로 일어난다.
     *
     * <p>여기서 봐야 할 것은 예외가 아니라 <b>부모가 되돌아갔는가</b>다. 두 문장이 같은
     * 트랜잭션이 아니면 부모는 회전됨으로 남고 자식은 없는 계열이 되어, 사용자는 재로그인
     * 외에는 빠져나올 길이 없다.
     */
    @Test
    void 자식_INSERT가_실패하면_부모의_회전_표시도_되돌아간다() {
        TokenCollectionDto issued = login();
        RefreshTokenPayload parent = payloadOf(issued.refreshToken());
        insertDecoyChild(parent.jwtId());

        assertThatThrownBy(() -> refreshTokenService.rotate(parent))
                .isInstanceOf(DuplicateKeyException.class);

        Map<String, Object> parentRow = committedRows(
                "SELECT * FROM refresh_token WHERE jwt_id = ?", parent.jwtId()).getFirst();
        assertThat(parentRow.get("rotated_at")).isNull();
        assertThat(parentRow.get("grace_expires_at")).isNull();
        // 미끼 하나 말고는 자식이 생기지 않았다
        assertThat(committedRows(
                "SELECT * FROM refresh_token WHERE parent_jwt_id = ?", parent.jwtId()))
                .hasSize(1);

        // 부모는 여전히 ACTIVE라 미끼를 치우면 정상적으로 회전한다 — 롤백이 온전했다는 뜻이다
        jdbcTemplate.update("DELETE FROM refresh_token WHERE jwt_id = 'decoy-child'");
        assertThat(refreshTokenService.rotate(parent)).isInstanceOf(RotationResult.Rotated.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 7 — 폐기 대 회전/발급의 양방향 순서
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>순서 ①: 폐기가 먼저 커밋된다.</b> 회전은 이미 ACTIVE를 보고 진입했지만 잠금을 늦게
     * 얻었다 — 그래서 조건부 UPDATE가 0행을 내고, 재분류에서 REVOKED를 본다. 재사용 판정이고,
     * 회전은 <b>일어나지 않는다</b>.
     *
     * <p>"이미 읽었으니 통과"가 되면 로그아웃한 기기가 한 번 더 토큰을 받아 간다 —
     * 분기용 조회가 판정이 아니라는 사실이 여기서 값을 갖는다.
     */
    @Test
    void 폐기가_먼저_커밋되면_진행_중이던_회전은_재사용으로_끝난다() throws Exception {
        TokenCollectionDto issued = login();
        RefreshTokenPayload parent = payloadOf(issued.refreshToken());

        CountDownLatch rotatorParked = new CountDownLatch(1);
        CountDownLatch revokeCommitted = new CountDownLatch(1);
        gateLockUserForRacers(rotatorParked, revokeCommitted);

        Future<RotationResult> rotation = executor.submit(() -> refreshTokenService.rotate(parent));
        assertThat(rotatorParked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 회전이 ACTIVE를 읽고 멈춰 있는 사이에 로그아웃이 통째로 커밋된다
        authService.logout(userId, parent.familyId());
        assertThat(committedRowsOfUser())
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
        revokeCommitted.countDown();

        assertThat(rotation.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(RotationResult.ReuseDetected.class);
        // 자식은 생기지 않았다 — 회전이 아예 일어나지 않았다는 뜻이다
        assertThat(committedRows(
                "SELECT * FROM refresh_token WHERE parent_jwt_id = ?", parent.jwtId())).isEmpty();
    }

    /**
     * <b>순서 ②: 회전이 먼저 커밋된다.</b> 로그아웃은 회전이 진행 중일 때 출발했지만 잠금을 늦게
     * 얻었고, 그래서 <b>그 사이 태어난 자식까지</b> 끊는다. 계열 단위 폐기가
     * {@code family_id}로 도는 이유가 이것이다 — id 목록을 미리 읽어 두면 그 사이 태어난 행을 놓친다.
     */
    @Test
    void 회전이_먼저_커밋돼도_뒤이은_계열_폐기가_새_자식까지_끊는다() throws Exception {
        TokenCollectionDto issued = login();
        RefreshTokenPayload parent = payloadOf(issued.refreshToken());

        CountDownLatch revokerParked = new CountDownLatch(1);
        CountDownLatch rotationCommitted = new CountDownLatch(1);
        gateLockUserForRacers(revokerParked, rotationCommitted);

        Future<Integer> revoke = executor.submit(
                () -> refreshTokenService.revokeFamily(userId, parent.familyId()));
        assertThat(revokerParked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        RotationResult rotated = refreshTokenService.rotate(parent);
        assertThat(rotated).isInstanceOf(RotationResult.Rotated.class);
        rotationCommitted.countDown();

        // 부모와 자식 둘 다 끊긴다
        assertThat(revoke.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(committedRowsOfUser())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());

        String child = ((RotationResult.Rotated) rotated).tokens().refreshToken();
        assertThatThrownBy(() -> authService.refreshToken(child))
                .isInstanceOf(JwtTokenException.class)
                .extracting(e -> ((JwtTokenException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
    }

    /**
     * <b>전체 폐기가 커밋된 뒤 잠금을 얻는 새 로그인은 허용된다.</b> 폐기는 이미 있는 계열을 끊는
     * 것이지 사용자를 막는 것이 아니다 — 막으면 재사용 감지 한 번이 계정 잠금이 된다.
     *
     * <p><b>계열 폐기가 아니라 사용자 전체 폐기여야 이 단언에 내용이 생긴다.</b> 계열 폐기는
     * {@code family_id}로 좁혀 돌기 때문에, 새 로그인이 만드는 다른 계열은 잠금 순서가 어떻게
     * 뒤바뀌든 살아남는다 — 그러면 "직렬화됐다"가 아니라 "애초에 겹치지 않는다"를 본 것이다.
     * {@code revokeAllByUserId}는 술어가 {@code user_id}뿐이라 <b>잠금 순서가 뒤집히면 새 계열도
     * 함께 끊긴다</b>. 그래서 여기서만 순서가 값을 갖는다.
     *
     * <p>재사용 감지를 실제로 일으켜 그 경로를 탄다 — 저장소를 직접 부르면 서비스가 여는
     * 트랜잭션 경계가 빠져 "커밋된 뒤"라는 말이 약해진다.
     */
    @Test
    void 전체_폐기가_커밋된_뒤_잠금을_얻는_새_로그인은_살아남는다() throws Exception {
        TokenCollectionDto old = login();
        RefreshTokenPayload oldPayload = payloadOf(old.refreshToken());
        refreshTokenService.rotate(oldPayload);   // 부모를 유예 상태로 만든다

        CountDownLatch loginParked = new CountDownLatch(1);
        CountDownLatch revokeCommitted = new CountDownLatch(1);
        gateLockUserForRacers(loginParked, revokeCommitted);

        Future<TokenCollectionDto> newLogin =
                executor.submit(() -> refreshTokenService.issue(userId, SocialPlatform.KAKAO));
        assertThat(loginParked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 로그인이 잠금을 기다리는 사이에 유예가 끝나고, 옛 부모로 들어온 요청이
        // 사용자 전체 폐기를 커밋한다
        clock.advance(authProperties.getRotationGrace());
        assertThatThrownBy(() -> authService.refreshToken(old.refreshToken()))
                .isInstanceOf(JwtTokenException.class)
                .extracting(e -> ((JwtTokenException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        assertThat(committedRowsOfUser())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
        revokeCommitted.countDown();

        TokenCollectionDto fresh = newLogin.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(committedRows("SELECT * FROM refresh_token WHERE jwt_id = ?",
                payloadOf(fresh.refreshToken()).jwtId()).getFirst().get("revoked_at")).isNull();
        // 그리고 그 새 계열은 정상적으로 회전한다
        assertThat(refreshTokenService.rotate(payloadOf(fresh.refreshToken())))
                .isInstanceOf(RotationResult.Rotated.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 트랜잭션 경계 — 호출자의 트랜잭션에 조용히 참여하지 않는다
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>회전은 호출자의 트랜잭션에 참여하지 않는다.</b> 참여하면 스프링이 격리 수준 지정을
     * 조용히 무시하고 저장소 기본값(REPEATABLE READ)에서 돌아, CAS에 진 요청이 다시 읽은 행에서
     * <em>자기가 처음 본 값</em>을 본다 — 멀쩡한 경쟁이 정합성 오류 500이 되는 자리다.
     *
     * <p>이것을 <b>동작으로</b> 확인한다. 바깥 트랜잭션을 열어 회전을 부른 뒤 그 바깥을
     * <b>롤백</b>시키고, 독립 커넥션으로 자식 행이 남아 있는지 본다. {@code REQUIRED}로 바꾸면
     * 회전이 바깥에 얹혀 함께 되돌아가므로 이 단언이 깨진다.
     */
    @Test
    void 회전은_바깥_트랜잭션이_롤백돼도_자기_커밋을_지킨다() {
        TokenCollectionDto issued = login();
        RefreshTokenPayload parent = payloadOf(issued.refreshToken());

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            assertThat(refreshTokenService.rotate(parent))
                    .isInstanceOf(RotationResult.Rotated.class);
            status.setRollbackOnly();
        });

        assertThat(committedRows(
                "SELECT * FROM refresh_token WHERE parent_jwt_id = ?", parent.jwtId())).hasSize(1);
        assertThat(committedRows("SELECT * FROM refresh_token WHERE jwt_id = ?", parent.jwtId())
                .getFirst().get("rotated_at")).isNotNull();
    }

    /**
     * 경계의 <b>선언</b>도 함께 못 박는다. 위 테스트는 전파를 잡지만 격리 수준은 잡지 못한다 —
     * 바깥 트랜잭션이 없는 정상 경로에서 {@code READ_COMMITTED}가 빠지면
     * {@code CAS에_진_요청은_최신_부모를_다시_읽는다}가 깨지므로 실측이 존재하지만,
     * 그 연결이 한 다리 건너 있어 여기서 애노테이션 자체를 읽어 둔다.
     */
    @Test
    void 상태를_바꾸는_메서드는_전부_REQUIRES_NEW_READ_COMMITTED다() throws Exception {
        for (String method : new String[] {"issue", "issueForAdmin", "rotate", "revokeFamily"}) {
            Transactional annotation = java.util.Arrays.stream(
                            RefreshTokenService.class.getMethods())
                    .filter(m -> m.getName().equals(method))
                    .findFirst().orElseThrow()
                    .getAnnotation(Transactional.class);

            assertThat(annotation).as("%s의 @Transactional", method).isNotNull();
            assertThat(annotation.propagation()).as("%s의 전파", method)
                    .isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(annotation.isolation()).as("%s의 격리", method)
                    .isEqualTo(Isolation.READ_COMMITTED);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 스파이 배선
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>경쟁자 스레드만</b> {@code lockUser} 앞에서 barrier를 만난다. 메인 스레드의 로그인·폐기는
     * 가로막지 않는다 — 둘 다 같은 메서드를 지나므로 스레드 이름으로 가른다.
     */
    private void gateLockUserForRacers(CyclicBarrier barrier) {
        willAnswer(invocation -> {
            if (isRacerThread()) {
                barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).given(spiedRepository()).lockUser(userId);
    }

    /** 경쟁자 스레드를 세워 두고, 메인 스레드가 순서를 정한다. */
    private void gateLockUserForRacers(CountDownLatch parked, CountDownLatch resume) {
        willAnswer(invocation -> {
            if (isRacerThread()) {
                parked.countDown();
                assertThat(resume.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).given(spiedRepository()).lockUser(userId);
    }

    /** CAS의 반환값을 기록한다 — 1행과 0행이 실제로 갈렸다는 증거가 이 목록이다. */
    private void recordCasResults(List<Integer> sink) {
        willAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            sink.add((Integer) result);
            return result;
        }).given(spiedRepository()).markRotated(anyLong(), anyLong(), anyLong(), anyLong());
    }

    private static boolean isRacerThread() {
        return Thread.currentThread().getName().startsWith(NamedThreadFactory.PREFIX);
    }

    /**
     * {@code @Repository}는 {@code PersistenceExceptionTranslationPostProcessor}가 프록시로 감쌀 수
     * 있다. 감싸지 않았다면 이 호출은 참조를 그대로 돌려준다.
     */
    private RefreshTokenRepository spiedRepository() {
        return AopTestUtils.getUltimateTargetObject(refreshTokenRepository);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<RotationResult> runConcurrently(
            Callable<RotationResult> first, Callable<RotationResult> second) throws Exception {
        Future<RotationResult> a = executor.submit(first);
        Future<RotationResult> b = executor.submit(second);
        return List.of(a.get(AWAIT_SECONDS, TimeUnit.SECONDS),
                b.get(AWAIT_SECONDS, TimeUnit.SECONDS));
    }

    private void insertDecoyChild(String parentJwtId) {
        jdbcTemplate.update("""
                INSERT INTO refresh_token
                    (user_id, family_id, jwt_id, parent_jwt_id, platform, token_format_version,
                     issued_at, expires_at)
                VALUES (?, 'decoy-family', 'decoy-child', ?, 'KAKAO', ?, ?, ?)
                """,
                userId, parentJwtId, JwtTokenProvider.TOKEN_FORMAT_VERSION,
                clock.instant().getEpochSecond(), clock.instant().getEpochSecond() + 3_600);
    }

    private TokenCollectionDto login() {
        return refreshTokenService.issue(userId, SocialPlatform.KAKAO);
    }

    private RefreshTokenPayload payloadOf(String refreshToken) {
        return jwtTokenProvider.parseRefreshToken(refreshToken);
    }

    private List<Map<String, Object>> committedRows(String sql, Object... args) {
        return readCommittedRows(sql, args);
    }

    private List<Map<String, Object>> committedRowsOfUser() {
        return readCommittedRows(
                "SELECT * FROM refresh_token WHERE user_id = ? ORDER BY id", userId);
    }

    private long createUser(String suffix) {
        String nickname = NICKNAME_PREFIX + suffix;
        jdbcTemplate.update("DELETE FROM users WHERE nickname = ?", nickname);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    /** 스레드 이름이 "경쟁자인가"의 판정 근거다 — 메인 스레드의 같은 호출은 가로막히지 않는다. */
    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {

        private static final String PREFIX = "refresh-racer-";
        private static final AtomicInteger SEQ = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, PREFIX + SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
