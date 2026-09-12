package org.sopt.solply_server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.sopt.solply_server.domain.auth.service.facade.AuthTokenCleanupFacade;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.support.AuthMySqlSupport;
import org.sopt.solply_server.support.MutableClock;
import org.sopt.solply_server.support.MutableClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 보존 정리 — <b>지우는 단위도 고르는 단위도 계열 전체다.</b>
 *
 * <p>토큰 단위로 지우면 안 되는 이유가 이 클래스의 주제다. 회전된 부모를 먼저 지우면 그 부모로
 * 들어온 재사용 요청이 "행이 없다"({@code AUTH-014})로 보여 <b>전체 폐기가 일어나지 않는다</b> —
 * 이력이 곧 재사용 판정의 근거이므로, 계열 안에 살아 있는 토큰이 하나라도 있으면 부모 이력도
 * 함께 남아야 한다.
 *
 * <p>행을 SQL로 직접 심는다. 시각 조합(만료 시각·보존 경계)을 정확히 잡아야 하는데 발급 경로를
 * 거치면 그 값이 시계와 TTL의 함수가 되어 경계를 1초 단위로 겨눌 수 없다.
 */
@SpringBootTest
@Import(MutableClockConfig.class)
class AuthTokenCleanupIT extends AuthMySqlSupport {

    private static final String NICKNAME_PREFIX = "정리IT유저";
    private static final String LOCK_NAME = "auth-token-cleanup";

    @Autowired private AuthTokenCleanupProcessor processor;
    @Autowired private AuthTokenCleanupFacade facade;
    @Autowired private AuthProperties authProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;

    private long userId;
    private long nowSeconds;

    @BeforeEach
    void setUp() {
        clock.setTo(MutableClockConfig.START);
        nowSeconds = clock.instant().getEpochSecond();
        userId = createUser();
        jdbcTemplate.update("DELETE FROM refresh_token WHERE family_id LIKE '정리IT-%'");
        // "몇 행을 지웠는가"를 단언하는 테스트가 있어서, 같은 컨테이너를 쓰는 다른 IT가 남긴
        // 만료 행 하나가 그 수치를 흔든다(실제로 AdminTempStateConsumeIT의 만료 픽스처 때문에
        // 한 번 깨졌다). 지우는 대상을 <b>이 회차가 만료로 셀 행</b>으로 좁혀, 살아 있는 임시
        // 데이터에는 손대지 않는다 — 테이블을 통째로 비우면 나중에 이 테이블을 쓰는 IT가 생겼을 때
        // 그쪽이 조용히 깨진다.
        jdbcTemplate.update("DELETE FROM admin_oauth_state WHERE expires_at < ?", clock.millis());
        jdbcTemplate.update("DELETE FROM admin_auth_code WHERE expires_at < ?", clock.millis());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM admin_oauth_state WHERE nonce LIKE '정리IT-%'");
        jdbcTemplate.update("DELETE FROM admin_auth_code WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE nickname LIKE ?", NICKNAME_PREFIX + "%");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 계열 보존
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>살아 있는 후손이 있으면 계열은 통째로 남는다.</b> 선택 조건이
     * {@code MAX(expires_at) < cutoff}라 이 계열은 애초에 고르는 단계에서 빠진다 —
     * "부모만 지우고 자식은 남긴다"는 예외 처리가 아니라, 고르지 않는 것이 계약이다.
     */
    @Test
    void 살아_있는_후손이_있는_계열은_부모_이력까지_남는다() {
        String family = insertFamily("살아있음",
                expiredLongAgo(),               // 회전된 부모 — 한참 전에 만료됐다
                nowSeconds + Duration.ofDays(7).toSeconds());  // 자식은 아직 산다

        int deleted = runRefreshCleanup();

        assertThat(deleted).isZero();
        assertThat(familyRows(family)).hasSize(2);
    }

    /** 계열 전체가 보존 기간을 넘겨 만료됐으면 부모와 자식이 <b>함께</b> 사라진다. */
    @Test
    void 전부_만료된_지_보존_기간이_지난_계열은_통째로_사라진다() {
        String family = insertFamily("전부만료", expiredLongAgo(), expiredLongAgo() + 10);

        int deleted = runRefreshCleanup();

        assertThat(deleted).isEqualTo(2);
        assertThat(familyRows(family)).isEmpty();
    }

    /**
     * <b>보존 기간 안이면 지우지 않는다.</b> 이력이 재사용 판정의 근거라, 만료됐다는 이유만으로
     * 곧바로 지우면 유예 종료 뒤 도착한 옛 토큰이 "행이 없다"로 읽혀 전체 폐기가 생략된다.
     */
    @Test
    void 만료됐어도_보존_기간_안이면_남긴다() {
        long retention = authProperties.getRefreshRetention().toSeconds();
        // cutoff = now - retention. 경계 정각은 `<` 조건에 걸리지 않으므로 남는다.
        String atBoundary = insertFamily("경계", nowSeconds - retention, nowSeconds - retention);
        String justInside = insertFamily("경계직전", nowSeconds - retention - 1, nowSeconds - retention - 1);

        int deleted = runRefreshCleanup();

        assertThat(familyRows(atBoundary)).hasSize(2);
        assertThat(familyRows(justInside)).isEmpty();
        assertThat(deleted).isEqualTo(2);
    }

    /**
     * 한 덩어리가 <b>덩어리 크기만큼만</b> 고른다 — 나머지는 다음 덩어리의 몫이다.
     * 한 문장에 다 담으면 그만큼의 행을 커밋까지 잠근다.
     *
     * <p><b>여기서 도는 루프는 테스트가 짠 것이지 파사드의 {@code runInBatches}가 아니다.</b>
     * 프로세서 한 덩어리의 계약(고른 만큼만 지우고 그만큼을 돌려준다)을 보는 자리이고,
     * 파사드가 실제로 덩어리를 이어 도는지는
     * {@code 정리_회차는_덩어리를_이어_돌아_남김없이_지운다}가 본다.
     */
    @Test
    void 한_덩어리는_덩어리_크기만큼만_고른다() {
        for (int i = 0; i < 5; i++) {
            insertFamily("대량" + i, expiredLongAgo(), expiredLongAgo() + 1);
        }

        // 첫 덩어리는 계열 둘(= 행 넷)만 가져간다 — 다섯 계열이 다 걸려 있어도
        assertThat(processor.deleteExpiredRefreshFamilyBatch(cutoffSeconds(), 2)).isEqualTo(4);

        int total = 4;
        int deleted;
        do {
            deleted = processor.deleteExpiredRefreshFamilyBatch(cutoffSeconds(), 2);
            total += deleted;
        } while (deleted > 0);

        assertThat(total).isEqualTo(10);
        assertThat(readCommittedRows(
                "SELECT * FROM refresh_token WHERE user_id = ?", userId)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 어드민 임시 데이터
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>정리와 소비는 집합이 겹치지 않는다.</b> 소비는 {@code expires_at > now}인 행만,
     * 정리는 {@code expires_at < now}인 행만 건드린다. 소비된 행이라도 만료 전이면 남는다 —
     * 그래야 "정리가 진행 중인 로그인을 끊는다"는 경쟁이 생기지 않는다.
     */
    @Test
    void 어드민_임시_데이터는_만료된_것만_지운다() {
        String expired = insertState("만료", clock.millis() - 1);
        String aliveButConsumed = insertState("소비됨", clock.millis() + 60_000);
        jdbcTemplate.update("UPDATE admin_oauth_state SET consumed_at = ? WHERE state = ?",
                clock.millis(), aliveButConsumed);
        String alive = insertState("살아있음", clock.millis() + 60_000);

        int deleted = processor.deleteExpiredAdminStateBatch(clock.millis(), 1_000);

        assertThat(deleted).isEqualTo(1);
        assertThat(stateExists(expired)).isFalse();
        assertThat(stateExists(aliveButConsumed)).isTrue();
        assertThat(stateExists(alive)).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 회차 전체
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>회차 하나가 세 대상을 모두 돈다.</b> 셋은 서로 독립이라 하나가 실패해도 나머지가 돌아야
     * 하고, 그 구조가 유지되는지는 "한 번 불러 셋이 다 줄었는가"로만 보인다.
     *
     * <p>이 테스트만 파사드를 직접 부른다 — {@code lockAtLeastFor}가 1분이라 같은 컨텍스트에서
     * 두 번 부르면 두 번째는 락을 잡지 못해 조용히 건너뛰어진다. 그 성질 자체는
     * {@code PlaceStatsSchedulerLockIT}가 같은 틀로 문다.
     */
    @Test
    void 정리_회차는_덩어리를_이어_돌아_refresh_계열과_어드민_임시_데이터를_남김없이_지운다() {
        List<String> doomed = List.of(
                insertFamily("회차만료1", expiredLongAgo(), expiredLongAgo() + 1),
                insertFamily("회차만료2", expiredLongAgo(), expiredLongAgo() + 1),
                insertFamily("회차만료3", expiredLongAgo(), expiredLongAgo() + 1));
        String surviving = insertFamily("회차생존", expiredLongAgo(), nowSeconds + 86_400);
        String expiredState = insertState("회차만료", clock.millis() - 1);
        String expiredCode = insertCode(clock.millis() - 1);
        String aliveCode = insertCode(clock.millis() + 60_000);

        // 덩어리를 계열 하나로 좁혀 파사드의 runInBatches가 실제로 여러 번 돌게 만든다 —
        // 기본값(1,000)이면 한 번에 끝나 그 루프를 지나지 않는다
        int originalBatchSize = authProperties.getCleanupBatchSize();
        authProperties.setCleanupBatchSize(1);
        ListAppender<ILoggingEvent> logs = attachAppender();
        try {
            facade.cleanupExpiredAuthData();
        } finally {
            detachAppender(logs);
            authProperties.setCleanupBatchSize(originalBatchSize);
        }

        assertThat(doomed).allSatisfy(family -> assertThat(familyRows(family)).isEmpty());
        assertThat(familyRows(surviving)).hasSize(2);
        assertThat(stateExists(expiredState)).isFalse();
        assertThat(codeExists(expiredCode)).isFalse();
        assertThat(codeExists(aliveCode)).isTrue();

        // 시작·완료 로그가 짝을 이룬다 — 없으면 "도는 중"과 "등록되지 않음"이 구분되지 않는다
        List<String> messages = logs.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(messages).anyMatch(m -> m.contains("인증 보존 정리 배치 시작"));
        assertThat(messages).anyMatch(m -> m.contains("인증 보존 정리 배치 완료"));
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);

        // 락 이름이 다른 회차와 갈려 있어야 한다
        assertThat(jdbcTemplate.queryForList(
                "SELECT name FROM shedlock WHERE name = ?", String.class, LOCK_NAME))
                .containsExactly(LOCK_NAME);
        jdbcTemplate.update("DELETE FROM shedlock WHERE name = ?", LOCK_NAME);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int runRefreshCleanup() {
        return processor.deleteExpiredRefreshFamilyBatch(cutoffSeconds(), 1_000);
    }

    private long cutoffSeconds() {
        return clock.instant().minus(authProperties.getRefreshRetention()).getEpochSecond();
    }

    /** 보존 기간을 훌쩍 넘긴 만료 시각. */
    private long expiredLongAgo() {
        return cutoffSeconds() - Duration.ofDays(1).toSeconds();
    }

    /** 부모 → 자식 두 행짜리 계열 하나. 부모는 회전된 상태로 심는다(이력의 모양 그대로). */
    private String insertFamily(String label, long parentExpiresAt, long childExpiresAt) {
        String family = "정리IT-" + label;
        String parentJti = UUID.randomUUID().toString();
        String childJti = UUID.randomUUID().toString();
        long rotatedAt = (parentExpiresAt - 60) * 1_000L;
        jdbcTemplate.update("""
                INSERT INTO refresh_token
                    (user_id, family_id, jwt_id, parent_jwt_id, platform, token_format_version,
                     issued_at, expires_at, rotated_at, grace_expires_at)
                VALUES (?, ?, ?, NULL, 'KAKAO', ?, ?, ?, ?, ?)
                """,
                userId, family, parentJti, JwtTokenProvider.TOKEN_FORMAT_VERSION,
                parentExpiresAt - 3_600, parentExpiresAt, rotatedAt, rotatedAt + 3_000);
        jdbcTemplate.update("""
                INSERT INTO refresh_token
                    (user_id, family_id, jwt_id, parent_jwt_id, platform, token_format_version,
                     issued_at, expires_at)
                VALUES (?, ?, ?, ?, 'KAKAO', ?, ?, ?)
                """,
                userId, family, childJti, parentJti, JwtTokenProvider.TOKEN_FORMAT_VERSION,
                childExpiresAt - 3_600, childExpiresAt);
        return family;
    }

    private List<Map<String, Object>> familyRows(String family) {
        return readCommittedRows(
                "SELECT * FROM refresh_token WHERE family_id = ? ORDER BY id", family);
    }

    private String insertState(String label, long expiresAtMillis) {
        String state = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO admin_oauth_state (state, nonce, expires_at) VALUES (?, ?, ?)",
                state, "정리IT-" + label, expiresAtMillis);
        return state;
    }

    private boolean stateExists(String state) {
        return !readCommittedRows("SELECT state FROM admin_oauth_state WHERE state = ?", state)
                .isEmpty();
    }

    private String insertCode(long expiresAtMillis) {
        String code = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO admin_auth_code (auth_code, user_id, platform, expires_at)
                VALUES (?, ?, 'KAKAO', ?)
                """, code, userId, expiresAtMillis);
        return code;
    }

    private boolean codeExists(String code) {
        return !readCommittedRows("SELECT auth_code FROM admin_auth_code WHERE auth_code = ?", code)
                .isEmpty();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AuthTokenCleanupFacade.class)).addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AuthTokenCleanupFacade.class)).detachAppender(appender);
    }

    private long createUser() {
        String nickname = NICKNAME_PREFIX + "주";
        jdbcTemplate.update("DELETE FROM users WHERE nickname = ?", nickname);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }
}
