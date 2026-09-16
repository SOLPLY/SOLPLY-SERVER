package org.sopt.solply_server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.sopt.solply_server.domain.auth.entity.RefreshTokenRow;
import org.sopt.solply_server.domain.auth.entity.RefreshTokenState;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.user.dto.request.UserWithdrawRequest;
import org.sopt.solply_server.domain.user.entity.WithdrawReason;
import org.sopt.solply_server.domain.user.service.UserWithdrawService;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.EntityNotFoundException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.sopt.solply_server.global.exception.JwtTokenException;
import org.sopt.solply_server.global.exception.UnauthorizedException;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenMaterial;
import org.sopt.solply_server.global.jwt.dto.RefreshTokenPayload;
import org.sopt.solply_server.global.jwt.dto.TokenCollectionDto;
import org.sopt.solply_server.support.AuthMySqlSupport;
import org.sopt.solply_server.support.MutableClock;
import org.sopt.solply_server.support.MutableClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * refresh 계열의 <b>정상 수명 전체</b>를 실제 MySQL 위에서 문다 — 재구성, 시각 경계, 상태 우선순위,
 * 연속 회전, 재사용 전체 폐기, 로그아웃과 탈퇴.
 *
 * <p><b>H2가 아니라 Testcontainers MySQL인 이유</b>는 이 클래스가 아니라 경쟁 IT에 있지만
 * ({@code RefreshTokenRotationConcurrencyIT}), 여기서도 같은 스키마·같은 타입 위에서 돌아야
 * {@code BIGINT} 정수 초의 왕복과 {@code parent_jwt_id} UNIQUE가 실재한다.
 *
 * <p><b>시계를 손으로 민다</b>({@link MutableClock}). 유예가 3초라 실제 시간으로는
 * "유예 종료 정각"을 찍을 수 없고, {@code Thread.sleep}으로 근사하면 "3초를 넘겼다"와
 * "테스트가 느렸다"가 구분되지 않는다.
 *
 * <p><b>커밋 여부를 묻는 단언은 전부 독립 커넥션으로 읽는다</b>({@code readCommittedRows}) —
 * 같은 커넥션으로 읽으면 아직 커밋되지 않은 자기 변경이 보인다.
 */
@SpringBootTest
@Import(MutableClockConfig.class)
class RefreshTokenLifecycleIT extends AuthMySqlSupport {

    private static final String NICKNAME_PREFIX = "회전IT유저";

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AuthService authService;
    @Autowired private AuthProperties authProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;
    @Autowired private UserWithdrawService userWithdrawService;

    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        clock.setTo(MutableClockConfig.START);
        userId = createUser("주", "USER");
        otherUserId = createUser("타", "USER");
    }

    /**
     * 이 IT는 테스트 트랜잭션이 없어 모든 쓰기가 커밋된다({@code REQUIRES_NEW}를 관찰하려면
     * 그래야 한다). 그래서 픽스처를 <b>매번 직접 지운다</b> — users 행을 통째로 없애는 테스트가
     * 있어 refresh 쪽을 먼저 지우는 순서가 계약이다(뒤집으면 주인 없는 행이 남는다).
     */
    @AfterEach
    void cleanUpFixtures() {
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (?, ?)", userId, otherUserId);
        // 탈퇴 경로가 남기는 행. users를 지우기 전에 먼저 걷어야 FK가 막지 않는다
        jdbcTemplate.update("DELETE FROM user_withdraws WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbcTemplate.update("DELETE FROM social_user_info WHERE user_id IN (?, ?)",
                userId, otherUserId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userId, otherUserId);
        jdbcTemplate.update("DELETE FROM users WHERE nickname LIKE ?", NICKNAME_PREFIX + "%");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 14 — 실제 MySQL 마이그레이션
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("V41 마이그레이션")
    class Migration {

        /**
         * <b>refresh_token은 JPA 엔티티가 아니라 {@code ddl-auto: validate}가 보지 못한다.</b>
         * 엔티티↔마이그레이션 불일치를 잡아 주던 장치가 이 테이블에는 없으므로, 재구성과 회전이
         * 기대는 성질을 스키마에서 직접 확인한다.
         */
        @Test
        void V41이_실제로_적용돼_세_테이블이_선다() {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT success FROM flyway_schema_history WHERE version = '41'
                    """, Boolean.class)).isTrue();

            assertThat(jdbcTemplate.queryForList("""
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN ('refresh_token', 'admin_oauth_state', 'admin_auth_code')
                     ORDER BY table_name
                    """, String.class))
                    .containsExactly("admin_auth_code", "admin_oauth_state", "refresh_token");
        }

        /**
         * <b>정수 초 왕복이 컬럼 타입 위에 서 있다.</b> {@code issued_at}·{@code expires_at}이
         * {@code DATETIME}이었다면 시간대와 반올림이 끼어들어 재구성이 원본과 갈린다.
         */
        @Test
        void 시각_컬럼은_전부_BIGINT다() {
            assertThat(jdbcTemplate.queryForList("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'refresh_token'
                       AND column_name IN ('issued_at','expires_at','rotated_at',
                                           'grace_expires_at','revoked_at')
                       AND data_type <> 'bigint'
                    """, String.class)).isEmpty();
        }

        /**
         * <b>{@code parent_jwt_id} UNIQUE가 두 갈래를 막는다.</b> 조건부 UPDATE가 이미 승자를 하나로
         * 좁히지만, 그 판정이 틀렸을 때 조용히 계열이 갈라지는 대신 INSERT가 죽게 하는 것이 이
         * 제약의 일이다({@code RefreshTokenRotationConcurrencyIT}가 실제로 그 경로를 탄다).
         */
        @Test
        void 신원과_부모_유일성_인덱스가_걸려_있다() {
            assertThat(uniqueIndexNames()).contains(
                    "ux_refresh_token_jwt_id", "ux_refresh_token_parent_jwt_id");
        }

        /** 로그인 직후 행은 부모가 없다 — MySQL의 UNIQUE가 NULL을 중복으로 보지 않는 덕이다. */
        @Test
        void 부모_없는_행은_여럿일_수_있다() {
            login();
            login();
            login();

            assertThat(committedFamilyRows(userId))
                    .hasSize(3)
                    .allSatisfy(row -> assertThat(row.get("parent_jwt_id")).isNull());
        }

        private List<String> uniqueIndexNames() {
            return jdbcTemplate.queryForList("""
                    SELECT DISTINCT index_name FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = 'refresh_token'
                       AND non_unique = 0
                    """, String.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 3 — 재구성과 정밀도
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("재구성 — 원문을 저장하지 않는다")
    class Reconstruction {

        /**
         * <b>이 단언이 "원문을 저장하지 않는다"의 근거 전부다.</b> 행에 남은 값만으로 다시 만든
         * 문자열이 발급된 문자열과 바이트까지 같아야, 유예 중에 돌려주는 토큰이 "직전에 못 받은
         * 그 토큰"이 된다. DB 왕복에서 정수 초가 한 번이라도 다른 타입으로 접히면 여기서 깨진다.
         */
        @Test
        void DB_왕복_후_바이트까지_같은_refresh_문자열이_나온다() {
            TokenCollectionDto issued = login();

            // 왕복을 실제로 끼운다 — 애플리케이션 풀이 아닌 <b>독립 커넥션</b>으로 커밋된 행을 읽고,
            // 그 값만으로 재료를 다시 세운다. 문자열 비교가 아니라 바이트 비교인 것도 계약이다:
            // 근거가 라이브러리의 직렬화 구현에 기대고 있어 버전을 올리면 조용히 깨질 수 있다.
            Map<String, Object> committed = committedRow(jtiOf(issued.refreshToken()));
            RefreshTokenMaterial fromDb = new RefreshTokenMaterial(
                    ((Number) committed.get("user_id")).longValue(),
                    SocialPlatform.valueOf((String) committed.get("platform")),
                    (String) committed.get("family_id"),
                    (String) committed.get("jwt_id"),
                    ((Number) committed.get("token_format_version")).intValue(),
                    ((Number) committed.get("issued_at")).longValue(),
                    ((Number) committed.get("expires_at")).longValue());

            assertThat(jwtTokenProvider.serializeRefreshToken(fromDb).getBytes(StandardCharsets.UTF_8))
                    .isEqualTo(issued.refreshToken().getBytes(StandardCharsets.UTF_8));
            // 저장소가 읽어 오는 길도 같은 값이어야 한다 — 두 경로가 갈리면 재구성이 갈린다
            RefreshTokenRow row = rowOf(issued.refreshToken());
            assertThat(row.toMaterial()).isEqualTo(fromDb);
        }

        /** 저장된 정수 초가 토큰이 주장하는 값과 <b>같은 수</b>여야 한다 — 재구성이 그 위에 선다. */
        @Test
        void 저장된_발급_만료_시각은_JWT의_정수_초와_같다() {
            TokenCollectionDto issued = login();
            RefreshTokenPayload payload = jwtTokenProvider.parseRefreshToken(issued.refreshToken());

            Map<String, Object> row = committedRow(payload.jwtId());

            assertThat(((Number) row.get("issued_at")).longValue())
                    .isEqualTo(payload.issuedAtEpochSecond())
                    .isEqualTo(MutableClockConfig.START.getEpochSecond());
            assertThat(((Number) row.get("expires_at")).longValue())
                    .isEqualTo(payload.expiresAtEpochSecond());
        }

        /**
         * 클레임 순서가 곧 JSON 키 순서이고, 순서가 바뀌면 같은 값이 다른 바이트가 된다.
         * <b>순서를 바꾸는 것은 새 형식이고 {@code TOKEN_FORMAT_VERSION}이 함께 올라가야 한다</b> —
         * 그 계약을 여기서 못 박는다.
         */
        @Test
        void 헤더와_클레임의_순서가_고정돼_있다() {
            TokenCollectionDto issued = login();

            assertThat(jsonKeysOf(issued.refreshToken(), 0)).containsExactly("alg");
            assertThat(decodeSegment(issued.refreshToken(), 0)).contains("\"HS512\"");
            assertThat(jsonKeysOf(issued.refreshToken(), 1))
                    .containsExactly("iss", "aud", "sub", "jti", "type", "platform", "fid", "ver",
                            "iat", "exp");
            assertThat(jsonKeysOf(issued.accessToken(), 1))
                    .containsExactly("iss", "aud", "sub", "type", "platform", "role", "fid", "ver",
                            "iat", "exp");
        }

        /**
         * <b>유예 재발급은 쓰기가 하나도 없다.</b> 부모의 유예를 늘리거나 자식의 만료를 미루면
         * 옛 토큰 하나로 계열을 무한히 살려 둘 수 있다.
         */
        @Test
        void 유예_재발급은_부모의_유예도_자식의_만료도_연장하지_않는다() {
            TokenCollectionDto issued = login();
            String parentJti = jtiOf(issued.refreshToken());
            TokenCollectionDto child = rotate(issued.refreshToken());
            List<Map<String, Object>> before = committedFamilyRows(userId);

            clock.advance(Duration.ofMillis(1));
            TokenCollectionDto regrant = rotate(issued.refreshToken());

            // 돌려받은 refresh는 자식의 것과 바이트까지 같다
            assertThat(regrant.refreshToken()).isEqualTo(child.refreshToken());
            // access만 새로 났다 — 시계가 1ms 움직였지만 iat는 정수 초라 값은 같을 수 있다
            assertThat(regrant.accessToken()).isNotBlank();
            // 그리고 행은 하나도 바뀌지 않았다
            assertThat(committedFamilyRows(userId)).isEqualTo(before);
            assertThat(before).hasSize(2);
            assertThat(before.stream()
                    .filter(r -> parentJti.equals(r.get("jwt_id")))
                    .findFirst().orElseThrow().get("grace_expires_at"))
                    .isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 4 — 시각 경계와 상태 우선순위
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("시각 경계")
    class TimeBoundary {

        /** 유예 종료 <b>1ms 전</b>은 아직 유예다 — 같은 자식 문자열이 돌아온다. */
        @Test
        void 유예_종료_직전에는_같은_자식_문자열이_돌아온다() {
            TokenCollectionDto issued = login();
            TokenCollectionDto child = rotate(issued.refreshToken());

            clock.advance(authProperties.getRotationGrace().minusMillis(1));

            assertThat(rotate(issued.refreshToken()).refreshToken())
                    .isEqualTo(child.refreshToken());
        }

        /**
         * <b>유예 종료 정각은 이미 종료다</b>({@code now < grace_expires_at}가 유예의 정의).
         * 경계를 {@code <=}로 흘리면 재사용 감지가 1ms씩 늦어지는 것이 아니라 "정확히 그 순간"의
         * 판정이 반대로 뒤집힌다.
         */
        @Test
        void 유예_종료_정각에는_재사용으로_판정한다() {
            TokenCollectionDto issued = login();
            rotate(issued.refreshToken());

            clock.advance(authProperties.getRotationGrace());

            assertThat(refreshTokenService.rotate(payloadOf(issued.refreshToken())))
                    .isInstanceOf(RotationResult.ReuseDetected.class);
        }

        /**
         * DB 만료는 재사용이 아니다 — <b>폐기가 뒤따르지 않는다</b>. JWT가 아직 살아 있는데 행의
         * {@code expires_at}이 지난 상황(설정 변경 등)을 시계로 재현한다. JWT 자체가 만료되면
         * 여기까지 내려오지 않으므로, 이 분기는 서비스를 직접 불러야 관찰된다.
         */
        @Test
        void DB_만료는_폐기_없이_만료_오류다() {
            TokenCollectionDto issued = login();
            RefreshTokenPayload payload = payloadOf(issued.refreshToken());

            clock.setTo(java.time.Instant.ofEpochSecond(payload.expiresAtEpochSecond()));

            assertThatThrownBy(() -> refreshTokenService.rotate(payload))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_REFRESH_TOKEN);
            assertThat(committedFamilyRows(userId))
                    .allSatisfy(row -> assertThat(row.get("revoked_at")).isNull());
        }

        /**
         * <b>JWT가 만료되면 DB 재사용 처리를 하지 않는다.</b> 유예가 끝난 부모라도 토큰 자체가
         * 만료됐으면 그것은 공격의 신호가 아니라 시간의 결과다.
         */
        @Test
        void JWT가_만료되면_유예가_끝난_부모여도_전체_폐기하지_않는다() {
            TokenCollectionDto issued = login();
            rotate(issued.refreshToken());
            long exp = payloadOf(issued.refreshToken()).expiresAtEpochSecond();

            clock.setTo(java.time.Instant.ofEpochSecond(exp));

            assertThatThrownBy(() -> authService.refreshToken(issued.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_REFRESH_TOKEN);
            assertThat(committedFamilyRows(userId))
                    .allSatisfy(row -> assertThat(row.get("revoked_at")).isNull());
        }

        /**
         * 폐기는 만료보다 <b>앞선다</b> — 두 조건이 <b>동시에 참인</b> 행에서만 그 순서가 드러난다.
         *
         * <p>시계를 만료 <em>전</em>에 두면 만료 조건이 아예 성립하지 않아, 판정 순서를 뒤집어도
         * 테스트가 그대로 통과한다. 그래서 시계를 {@code exp} 정각으로 밀어 둘 다 참인 자리를
         * 만든다 — 그 지점에서 EXPIRED가 앞서면 결과가 {@code AUTH-004}(그냥 재로그인)가 되고,
         * REVOKED가 앞서면 재사용 판정이 된다. 둘은 다른 사건이고, 그 구분이 이 순서의 전부다.
         *
         * <p>서비스를 직접 부르는 이유는 이 시점에 JWT가 먼저 만료로 걸리기 때문이다
         * ({@code DB_만료는_폐기_없이_만료_오류다}와 같은 사정). 폐기 대상은 이미 없으므로
         * {@code revokedCount}가 아니라 <b>판정의 종류</b>를 본다.
         */
        @Test
        void 폐기가_만료보다_우선한다() {
            TokenCollectionDto issued = login();
            String jti = jtiOf(issued.refreshToken());
            refreshTokenService.revokeFamily(userId, familyIdOf(issued.refreshToken()));
            RefreshTokenPayload payload = payloadOf(issued.refreshToken());

            clock.setTo(java.time.Instant.ofEpochSecond(payload.expiresAtEpochSecond()));

            // 이 뒤로는 토큰 문자열을 다시 파싱하지 않는다 — 시계가 exp에 있어 JWT 단계에서
            // 먼저 거절되기 때문이다. 행은 이미 손에 쥔 jti로 읽는다.
            RefreshTokenRow row = refreshTokenRepository.findByJwtId(jti).orElseThrow();
            assertThat(committedRow(jti).get("revoked_at")).isNotNull();
            // 만료 조건도 참인 자리다 — 폐기 도장만 지우면 같은 행이 EXPIRED가 된다
            assertThat(row.state(clock.instant())).isEqualTo(RefreshTokenState.REVOKED);
            assertThat(withRevocationCleared(row).state(clock.instant()))
                    .isEqualTo(RefreshTokenState.EXPIRED);

            assertThat(refreshTokenService.rotate(payload))
                    .isInstanceOf(RotationResult.ReuseDetected.class);
        }

        /** 같은 행에서 폐기 도장만 뺀 사본. "둘 다 참인 자리"라는 말을 값으로 만든다. */
        private RefreshTokenRow withRevocationCleared(RefreshTokenRow row) {
            return new RefreshTokenRow(row.id(), row.userId(), row.familyId(), row.jwtId(),
                    row.parentJwtId(), row.platform(), row.tokenFormatVersion(),
                    row.issuedAtEpochSecond(), row.expiresAtEpochSecond(),
                    row.rotatedAtEpochMilli(), row.graceExpiresAtEpochMilli(), null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 5 — 연속 회전과 자식의 상태
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("연속 회전과 자식의 상태")
    class Chain {

        @Test
        void 연속_회전은_한_계열_안에서_사슬을_잇는다() {
            TokenCollectionDto t0 = login();
            String family = familyIdOf(t0.refreshToken());

            TokenCollectionDto t1 = rotate(t0.refreshToken());
            TokenCollectionDto t2 = rotate(t1.refreshToken());
            TokenCollectionDto t3 = rotate(t2.refreshToken());

            List<Map<String, Object>> rows = committedFamilyRows(userId);
            assertThat(rows).hasSize(4);
            assertThat(rows).allSatisfy(r -> assertThat(r.get("family_id")).isEqualTo(family));
            assertThat(rows).extracting(r -> r.get("jwt_id"))
                    .containsExactlyInAnyOrder(jtiOf(t0.refreshToken()), jtiOf(t1.refreshToken()),
                            jtiOf(t2.refreshToken()), jtiOf(t3.refreshToken()));
            // 마지막 하나만 미회전이다
            assertThat(rows).filteredOn(r -> r.get("rotated_at") == null)
                    .singleElement()
                    .satisfies(r -> assertThat(r.get("jwt_id")).isEqualTo(jtiOf(t3.refreshToken())));
            // 부모 사슬이 실제로 이어져 있다
            assertThat(committedRow(jtiOf(t3.refreshToken())).get("parent_jwt_id"))
                    .isEqualTo(jtiOf(t2.refreshToken()));
        }

        /**
         * 유예 중인 부모의 자식이 <b>이미 회전했으면</b> 그 문자열은 최신이 아니다.
         * 이것만으로 전체 폐기하지 않는다 — 클라이언트가 할 일은 재로그인이 아니라 최신 토큰 재시도다.
         */
        @Test
        void 유예_부모의_자식이_이미_회전했으면_SUPERSEDED다() {
            TokenCollectionDto t0 = login();
            TokenCollectionDto t1 = rotate(t0.refreshToken());
            rotate(t1.refreshToken());

            assertSupersededAndNoNewRevocation(t0.refreshToken());
        }

        @Test
        void 유예_부모의_자식이_폐기됐으면_SUPERSEDED다() {
            TokenCollectionDto t0 = login();
            TokenCollectionDto t1 = rotate(t0.refreshToken());
            jdbcTemplate.update("UPDATE refresh_token SET revoked_at = ? WHERE jwt_id = ?",
                    clock.millis(), jtiOf(t1.refreshToken()));

            assertSupersededAndNoNewRevocation(t0.refreshToken());
        }

        @Test
        void 유예_부모의_자식이_만료됐으면_SUPERSEDED다() {
            TokenCollectionDto t0 = login();
            TokenCollectionDto t1 = rotate(t0.refreshToken());
            jdbcTemplate.update("UPDATE refresh_token SET expires_at = ? WHERE jwt_id = ?",
                    clock.instant().getEpochSecond() - 1, jtiOf(t1.refreshToken()));

            assertSupersededAndNoNewRevocation(t0.refreshToken());
        }

        /**
         * 자식이 <b>없는</b> 유예 부모는 재사용이 아니라 정합성 오류다 — 공격의 증거가 아니라
         * 우리가 깨졌다는 증거이므로 401이 아니라 500이고, 전체 폐기도 하지 않는다.
         */
        @Test
        void 유예_부모에게_자식이_없으면_정합성_오류다() {
            TokenCollectionDto t0 = login();
            TokenCollectionDto t1 = rotate(t0.refreshToken());
            jdbcTemplate.update("DELETE FROM refresh_token WHERE jwt_id = ?", jtiOf(t1.refreshToken()));

            assertInconsistentAndNoNewRevocation(t0.refreshToken());
        }

        /** 회전 시각과 유예 종료 시각 중 한쪽만 채워진 행은 있을 수 없는 조합이다. */
        @Test
        void 회전_시각만_있고_유예_종료_시각이_없으면_정합성_오류다() {
            TokenCollectionDto t0 = login();
            rotate(t0.refreshToken());
            jdbcTemplate.update("UPDATE refresh_token SET grace_expires_at = NULL WHERE jwt_id = ?",
                    jtiOf(t0.refreshToken()));

            assertInconsistentAndNoNewRevocation(t0.refreshToken());
        }

        /** 서명은 맞는데 행이 없으면 폐기할 계열조차 특정할 수 없다 — 재사용 판정이 아니다. */
        @Test
        void 행이_없으면_NOT_FOUND다() {
            TokenCollectionDto t0 = login();
            jdbcTemplate.update("DELETE FROM refresh_token WHERE jwt_id = ?", jtiOf(t0.refreshToken()));

            assertThatThrownBy(() -> authService.refreshToken(t0.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 6 — 재사용 감지의 전체 폐기가 실제로 커밋된다
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("재사용 감지")
    class ReuseDetection {

        /**
         * <b>이 테스트의 핵심은 401이 아니라 커밋이다.</b> 폐기를 트랜잭션 안에서 예외로 알리면
         * 폐기가 통째로 롤백되고 경고만 나간다 — 탈취범의 토큰이 살아 있는 채로. 그래서 401을 받은
         * 뒤 <b>독립 커넥션</b>으로 행을 읽어, 그 폐기가 바깥에서 보이는지 확인한다.
         *
         * <p>폭도 함께 본다 — 회전된 부모와 <b>다른 계열</b>까지 전부다.
         */
        @Test
        void 유예가_끝난_부모로_다시_오면_모든_계열이_실제로_폐기된다() {
            TokenCollectionDto familyA = login();
            TokenCollectionDto familyB = login();
            TokenCollectionDto childA = rotate(familyA.refreshToken());

            clock.advance(authProperties.getRotationGrace());

            assertThatThrownBy(() -> authService.refreshToken(familyA.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);

            // 회전된 부모 · 그 자식 · 손대지 않은 다른 계열까지 전부 폐기됐고, 그것이 커밋됐다
            assertThat(committedFamilyRows(userId))
                    .hasSize(3)
                    .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
            assertThat(committedRow(jtiOf(childA.refreshToken())).get("revoked_at")).isNotNull();
            assertThat(committedRow(jtiOf(familyB.refreshToken())).get("revoked_at")).isNotNull();
        }

        /**
         * <b>정책을 값으로 고정한다</b>(검토 2-1·7-1). {@code revoked_at}은 누가 찍었는지를 남기지
         * 않으므로, 로그아웃이 찍은 도장과 재사용 감지가 찍은 도장이 같은 컬럼이다. 그래서
         * <b>로그아웃한 계열의 refresh로 재발급을 시도하면 다른 기기까지 끊긴다.</b>
         *
         * <p><b>이것은 로그아웃 자체의 동작이 아니다.</b> 로그아웃은 자기 계열만 끊고
         * ({@code 계열_로그아웃은_그_계열만_끊고_다른_기기는_살린다}), 다른 기기를 끊는 것은
         * <b>그 뒤에 도착한 지연 요청</b>이다. 두 사건을 갈라 두지 않으면 "로그아웃이 전체를
         * 끊는다"는 잘못된 요약이 남는다.
         *
         * <p>사용자가 명시적으로 고른 정책이므로 여기서 뒤집지 않는다. 다음 사람이 뒤집으려면
         * 이 테스트를 먼저 고쳐야 하고, 그 순간 결정이 다시 드러난다.
         */
        @Test
        void 로그아웃한_계열의_토큰으로_재발급을_시도하면_다른_계열까지_끊긴다() {
            TokenCollectionDto phone = login();
            TokenCollectionDto tablet = login();
            authService.logout(userId, familyIdOf(phone.refreshToken()));
            // 로그아웃 직후: 태블릿은 살아 있다
            assertThat(committedRow(jtiOf(tablet.refreshToken())).get("revoked_at")).isNull();

            assertThatThrownBy(() -> authService.refreshToken(phone.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);

            // 지연 요청 하나가 도착한 뒤: 태블릿도 끊겼다
            assertThat(committedRow(jtiOf(tablet.refreshToken())).get("revoked_at")).isNotNull();
        }

        /** 다른 사용자의 계열은 건드리지 않는다 — 폐기의 단위는 사용자다. */
        @Test
        void 전체_폐기는_다른_사용자를_건드리지_않는다() {
            TokenCollectionDto mine = login();
            TokenCollectionDto theirs = loginAs(otherUserId);
            rotate(mine.refreshToken());
            clock.advance(authProperties.getRotationGrace());

            assertThatThrownBy(() -> authService.refreshToken(mine.refreshToken()))
                    .isInstanceOf(JwtTokenException.class);

            assertThat(committedRow(jtiOf(theirs.refreshToken())).get("revoked_at")).isNull();
        }

        /** 전체 폐기 뒤의 새 로그인은 허용된다 — 새 계열이 열릴 뿐이다. */
        @Test
        void 전체_폐기_뒤의_새_로그인은_허용된다() {
            TokenCollectionDto old = login();
            rotate(old.refreshToken());
            clock.advance(authProperties.getRotationGrace());
            assertThatThrownBy(() -> authService.refreshToken(old.refreshToken()))
                    .isInstanceOf(JwtTokenException.class);

            TokenCollectionDto fresh = login();

            assertThat(committedRow(jtiOf(fresh.refreshToken())).get("revoked_at")).isNull();
            assertThat(familyIdOf(fresh.refreshToken())).isNotEqualTo(familyIdOf(old.refreshToken()));
            // 그리고 그 새 계열은 정상적으로 회전한다
            assertThat(rotate(fresh.refreshToken()).refreshToken()).isNotBlank();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 검증 기준 9 — 계열 로그아웃과 탈퇴
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("로그아웃과 탈퇴")
    class LogoutAndWithdrawal {

        @Test
        void 계열_로그아웃은_그_계열만_끊고_다른_기기는_살린다() {
            TokenCollectionDto phone = login();
            TokenCollectionDto tablet = login();

            authService.logout(userId, familyIdOf(phone.refreshToken()));

            assertThat(committedRow(jtiOf(phone.refreshToken())).get("revoked_at")).isNotNull();
            assertThat(committedRow(jtiOf(tablet.refreshToken())).get("revoked_at")).isNull();
            // 살아 있는 계열은 그대로 회전한다
            assertThat(rotate(tablet.refreshToken()).refreshToken()).isNotBlank();
        }

        /**
         * 같은 access로 반복 호출하는 클라이언트는 정상이다 — 두 번째 호출은 0행을 바꾸고 끝난다.
         * 여기서 404를 내면 "로그아웃은 몇 번을 해도 성공한다"는 계약이 깨진다.
         */
        @Test
        void 반복_로그아웃은_예외_없이_0행을_바꾼다() {
            TokenCollectionDto tokens = login();
            String family = familyIdOf(tokens.refreshToken());

            assertThat(refreshTokenService.revokeFamily(userId, family)).isEqualTo(1);
            assertThat(refreshTokenService.revokeFamily(userId, family)).isZero();
            assertThat(refreshTokenService.revokeFamily(userId, family)).isZero();
        }

        /** 로그아웃된 계열로 재발급을 시도하면 폐기된 토큰의 재사용이다. */
        @Test
        void 로그아웃된_계열의_refresh는_재사용으로_막힌다() {
            TokenCollectionDto tokens = login();
            authService.logout(userId, familyIdOf(tokens.refreshToken()));

            assertThatThrownBy(() -> authService.refreshToken(tokens.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        }

        /** 인증이 없으면(둘 중 하나라도 null) 401이다. 예전에는 익명 호출이 200을 받았다. */
        @Test
        void 인증_없는_로그아웃은_401이다() {
            assertThatThrownBy(() -> authService.logout(null, "family"))
                    .isInstanceOf(UnauthorizedException.class);
            assertThatThrownBy(() -> authService.logout(userId, null))
                    .isInstanceOf(UnauthorizedException.class);
        }

        /**
         * <b>탈퇴와 폐기가 한 트랜잭션이라는 것이 재가입의 안전을 만든다.</b> 소프트 삭제라 같은
         * user 행이 되살아나므로, 따로 커밋하면 그 틈에 재가입한 사용자에게 탈퇴 전 계열이 유효해진다.
         * 여기서는 탈퇴가 커밋된 뒤 행을 되살려(재가입의 관측 가능한 형태) 옛 계열이
         * <b>폐기된 채로 남는지</b>를 본다.
         */
        @Test
        void 탈퇴는_모든_계열을_끊고_재가입해도_옛_계열은_폐기된_채로_남는다() {
            TokenCollectionDto a = login();
            TokenCollectionDto b = login();

            withdraw(userId);
            // 소프트 삭제라 users 행은 남는다 — 그래서 재가입이 같은 행을 되살릴 수 있고,
            // 폐기가 탈퇴와 같은 트랜잭션이어야 하는 이유가 그것이다
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT is_deleted FROM users WHERE id = ?", Boolean.class, userId)).isTrue();

            assertThat(committedFamilyRows(userId))
                    .hasSize(2)
                    .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());

            // 재가입 — 같은 user 행이 되살아난다
            jdbcTemplate.update("UPDATE users SET is_deleted = false, deleted_at = NULL WHERE id = ?",
                    userId);

            assertThat(committedRow(jtiOf(a.refreshToken())).get("revoked_at")).isNotNull();
            assertThat(committedRow(jtiOf(b.refreshToken())).get("revoked_at")).isNotNull();
            assertThatThrownBy(() -> authService.refreshToken(a.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        }

        /**
         * <b>정책의 한계를 값으로 고정한다</b>(검토 7-1의 시나리오 B). 재가입한 사용자는 잠금에
         * 걸리지 않으므로 새 계열이 정상적으로 열린다. 그런데 탈퇴 전 계열의 <b>아직 만료되지 않은</b>
         * refresh가 뒤늦게 도착하면 그 행은 REVOKED이고, 정책상 재사용 판정이라
         * <b>방금 열린 새 계열까지 끊긴다</b>.
         *
         * <p>사용자가 고른 정책의 결과이지 결함이 아니다 — 다만 몰랐던 부작용으로 나중에
         * 발견되지 않도록 여기 남긴다.
         */
        @Test
        void 재가입_직후_옛_토큰이_도착하면_새_계열까지_끊긴다() {
            TokenCollectionDto beforeWithdrawal = login();
            withdraw(userId);
            jdbcTemplate.update("UPDATE users SET is_deleted = false, deleted_at = NULL WHERE id = ?",
                    userId);
            TokenCollectionDto afterRejoin = login();
            assertThat(committedRow(jtiOf(afterRejoin.refreshToken())).get("revoked_at")).isNull();

            assertThatThrownBy(() -> authService.refreshToken(beforeWithdrawal.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);

            assertThat(committedRow(jtiOf(afterRejoin.refreshToken())).get("revoked_at")).isNotNull();
        }

        /**
         * 탈퇴한 사용자에게는 새 토큰이 나가지 않는다 — 잠금이 소프트 삭제 행도 잡기 때문이다.
         *
         * <p><b>두 경로의 오류 코드가 갈리는 것이 계약이다</b>(검토 2-2). 발급은 404
         * ({@code NOT_FOUND_USER}), <b>재발급은 401 {@code AUTH-017}</b>이다. 재발급에 실패한
         * 클라이언트가 해야 할 일은 "저장된 토큰을 버리고 로그인 화면으로"이고 그 분기는 보통
         * 401에 걸려 있어, 404를 내보내면 죽은 토큰으로 재시도를 반복한다.
         */
        @Test
        void 탈퇴한_사용자의_발급은_404이고_재발급은_401_AUTH_017이다() {
            TokenCollectionDto tokens = login();
            jdbcTemplate.update(
                    "UPDATE users SET is_deleted = true, deleted_at = NOW() WHERE id = ?", userId);

            assertThatThrownBy(() -> refreshTokenService.issue(userId, SocialPlatform.KAKAO))
                    .isInstanceOf(EntityNotFoundException.class)
                    .extracting(e -> ((EntityNotFoundException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND_USER);

            assertThatThrownBy(() -> authService.refreshToken(tokens.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_USER_INACTIVE);
            assertThat(ErrorCode.REFRESH_TOKEN_USER_INACTIVE.getHttpStatus().value()).isEqualTo(401);
        }

        /**
         * <b>주인 없는 refresh도 같은 401이다.</b> 서명이 맞는 토큰을 들고 왔는데 그 주인이
         * 없다는 것은 자원 조회 실패가 아니라 자격이 끝났다는 뜻이다.
         */
        @Test
        void 주인이_사라진_refresh의_재발급도_401_AUTH_017이다() {
            TokenCollectionDto tokens = login();
            // 소프트 삭제가 아니라 행 자체를 없앤다 — 잠금 질의가 0행을 받는 경로다
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

            assertThatThrownBy(() -> authService.refreshToken(tokens.refreshToken()))
                    .isInstanceOf(JwtTokenException.class)
                    .extracting(e -> ((JwtTokenException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_USER_INACTIVE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>프로덕션 탈퇴 경로를 그대로 부른다.</b> 같은 불변식을 테스트 안에서 다시 짜면
     * {@code UserWithdrawService#withdraw}에서 {@code revokeAllByUserId} 한 줄을 지워도 아무 테스트도
     * 깨지지 않는다 — 그 한 줄이 "재가입으로 옛 계열이 살아나지 않는다"의 전부인데도.
     */
    private void withdraw(long id) {
        userWithdrawService.withdraw(id, new UserWithdrawRequest(WithdrawReason.NOT_USE, null));
    }

    /**
     * {@code AUTH-015}는 <b>이것만으로 전체 폐기하지 않는다</b>가 계약이다. 그래서 오류 코드만
     * 보지 않고, 호출 전후로 폐기된 행의 집합이 그대로인지를 함께 본다 — 픽스처가 미리 폐기해 둔
     * 행이 있어도 "새로 폐기된 행이 없다"는 말이 성립한다.
     */
    private void assertSupersededAndNoNewRevocation(String parentRefresh) {
        List<Object> revokedBefore = revokedJwtIds();

        assertThatThrownBy(() -> authService.refreshToken(parentRefresh))
                .isInstanceOf(JwtTokenException.class)
                .extracting(e -> ((JwtTokenException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_SUPERSEDED);

        assertThat(revokedJwtIds()).isEqualTo(revokedBefore);
    }

    private void assertInconsistentAndNoNewRevocation(String parentRefresh) {
        List<Object> revokedBefore = revokedJwtIds();

        assertThatThrownBy(() -> authService.refreshToken(parentRefresh))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_STATE_INCONSISTENT);

        assertThat(revokedJwtIds()).isEqualTo(revokedBefore);
    }

    private List<Object> revokedJwtIds() {
        return committedFamilyRows(userId).stream()
                .filter(row -> row.get("revoked_at") != null)
                .map(row -> row.get("jwt_id"))
                .toList();
    }

    private TokenCollectionDto login() {
        return loginAs(userId);
    }

    private TokenCollectionDto loginAs(long id) {
        return refreshTokenService.issue(id, SocialPlatform.KAKAO);
    }

    private TokenCollectionDto rotate(String refreshToken) {
        RotationResult result = refreshTokenService.rotate(payloadOf(refreshToken));
        assertThat(result).isInstanceOf(RotationResult.Rotated.class);
        return ((RotationResult.Rotated) result).tokens();
    }

    private RefreshTokenPayload payloadOf(String refreshToken) {
        return jwtTokenProvider.parseRefreshToken(refreshToken);
    }

    private String jtiOf(String refreshToken) {
        return payloadOf(refreshToken).jwtId();
    }

    private String familyIdOf(String refreshToken) {
        return payloadOf(refreshToken).familyId();
    }

    private RefreshTokenRow rowOf(String refreshToken) {
        return refreshTokenRepository.findByJwtId(jtiOf(refreshToken)).orElseThrow();
    }

    private Map<String, Object> committedRow(String jwtId) {
        List<Map<String, Object>> rows = readCommittedRows(
                "SELECT * FROM refresh_token WHERE jwt_id = ?", jwtId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> committedFamilyRows(long id) {
        return readCommittedRows(
                "SELECT * FROM refresh_token WHERE user_id = ? ORDER BY id", id);
    }

    private static String decodeSegment(String jwt, int index) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]),
                StandardCharsets.UTF_8);
    }

    /** JSON 최상위 키를 <b>나온 순서 그대로</b> 뽑는다 — 순서가 계약이므로 집합이 아니라 목록이다. */
    private static List<String> jsonKeysOf(String jwt, int index) {
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(decodeSegment(jwt, index));
        List<String> keys = new java.util.ArrayList<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private long createUser(String suffix, String role) {
        String nickname = NICKNAME_PREFIX + suffix;
        jdbcTemplate.update("DELETE FROM users WHERE nickname = ?", nickname);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES (?, ?)", role, nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }
}
