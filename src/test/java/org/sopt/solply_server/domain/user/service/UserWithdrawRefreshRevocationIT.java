package org.sopt.solply_server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.auth.repository.RefreshTokenRepository;
import org.sopt.solply_server.domain.auth.service.AuthService;
import org.sopt.solply_server.domain.auth.service.RefreshTokenService;
import org.sopt.solply_server.domain.auth.service.RotationResult;
import org.sopt.solply_server.domain.user.dto.request.UserWithdrawRequest;
import org.sopt.solply_server.domain.user.entity.WithdrawReason;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

/**
 * 탈퇴 — <b>실제 {@code UserWithdrawService.withdraw}를 부른다.</b>
 *
 * <p>저장소 메서드로 같은 불변식을 재현하면 "그 불변식이 옳다"까지만 보이고, 서비스가 그
 * 불변식을 <b>실제로 지키는지</b>는 보이지 않는다. 그 상태에서는 {@code withdraw}에서
 * {@code revokeAllByUserId} 한 줄을 지워도 아무 테스트도 깨지지 않는다 — 소프트 삭제라 같은
 * {@code users} 행이 재가입으로 되살아나므로, 그 한 줄이 "옛 계열이 살아나지 않는다"의 전부다.
 */
@SpringBootTest
@Import(MutableClockConfig.class)
class UserWithdrawRefreshRevocationIT extends AuthMySqlSupport {

    private static final UserWithdrawRequest REQUEST =
            new UserWithdrawRequest(WithdrawReason.NOT_USE, null);

    @Autowired private UserWithdrawService userWithdrawService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private AuthService authService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MutableClock clock;

    /** 탈퇴 트랜잭션 안에서 폐기를 터뜨려 소프트 삭제까지 되돌아가는지 보기 위한 지점. */
    @SpyBean private RefreshTokenRepository refreshTokenRepository;

    private final List<Long> createdUserIds = new ArrayList<>();

    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        clock.setTo(MutableClockConfig.START);
        userId = createUser();
        otherUserId = createUser();
    }

    /**
     * 탈퇴가 nickname을 {@code 탈퇴회원_<id>}로 바꾸므로 접두어로 지울 수 없다 — id로 지운다.
     * {@code user_withdraws}가 users를 FK로 참조하므로 순서가 계약이다.
     */
    @AfterEach
    void tearDown() {
        for (Long id : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", id);
            jdbcTemplate.update("DELETE FROM user_withdraws WHERE user_id = ?", id);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
        createdUserIds.clear();
    }

    /**
     * <b>소프트 삭제와 전체 폐기가 같은 커밋에 들어 있다.</b> 독립 커넥션으로 읽으므로 둘 다
     * 보인다는 것은 둘 다 커밋됐다는 뜻이다 — {@code revokeAllByUserId} 호출이 사라지면
     * 사용자는 삭제됐는데 계열은 살아 있는 상태가 관측된다.
     */
    @Test
    void 탈퇴는_소프트_삭제와_refresh_전체_폐기를_함께_커밋한다() {
        TokenCollectionDto phone = login(userId);
        TokenCollectionDto tablet = login(userId);

        userWithdrawService.withdraw(userId, REQUEST);

        assertThat(softDeleted(userId)).isTrue();
        assertThat(refreshRowsOf(userId))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
        assertThat(revokedAt(phone)).isNotNull();
        assertThat(revokedAt(tablet)).isNotNull();
    }

    /** 폐기의 단위는 사용자다 — 남의 계열은 그대로 돌아간다. */
    @Test
    void 탈퇴는_다른_사용자의_계열을_건드리지_않는다() {
        login(userId);
        TokenCollectionDto theirs = login(otherUserId);

        userWithdrawService.withdraw(userId, REQUEST);

        assertThat(revokedAt(theirs)).isNull();
        assertThat(refreshTokenService.rotate(payloadOf(theirs)))
                .isInstanceOf(RotationResult.Rotated.class);
    }

    /**
     * <b>재가입으로 행이 되살아나도 옛 계열은 죽은 채다.</b> 되살아난 사용자는 잠금에 걸리지
     * 않으므로 재발급이 사용자 판정을 통과하고, 그래서 행 상태 분류까지 내려간다 — 거기서
     * REVOKED로 읽혀 재사용 판정이 된다. 탈퇴가 폐기를 함께 커밋하지 않았다면 이 토큰은
     * 그대로 살아나 새 access를 받아 간다.
     */
    @Test
    void 재활성된_사용자의_옛_계열은_되살아나지_않는다() {
        TokenCollectionDto beforeWithdrawal = login(userId);
        userWithdrawService.withdraw(userId, REQUEST);

        reactivate(userId);

        assertThatThrownBy(() -> authService.refreshToken(beforeWithdrawal.refreshToken()))
                .isInstanceOf(JwtTokenException.class)
                .extracting(e -> ((JwtTokenException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
        // 되살아난 사용자 자체는 다시 로그인할 수 있다 — 폐기는 계정 잠금이 아니다
        assertThat(revokedAt(login(userId))).isNull();
    }

    /**
     * <b>반대 방향 — 폐기가 죽으면 소프트 삭제도 되돌아간다.</b> 둘이 한 트랜잭션이라는 말의
     * 관측 가능한 형태이고, 따로 커밋하는 구조로 바뀌면 "삭제는 됐는데 계열은 살아 있다"가
     * 남아 이 단언이 깨진다.
     */
    @Test
    void refresh_폐기가_죽으면_소프트_삭제도_함께_되돌아간다() {
        TokenCollectionDto tokens = login(userId);
        willThrow(new DataAccessResourceFailureException("폐기가 죽은 탈퇴"))
                .given(spiedRepository()).revokeAllByUserId(anyLong(), anyLong(), anyLong());

        assertThatThrownBy(() -> userWithdrawService.withdraw(userId, REQUEST))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(softDeleted(userId)).isFalse();
        assertThat(revokedAt(tokens)).isNull();
        assertThat(readCommittedRows(
                "SELECT id FROM user_withdraws WHERE user_id = ?", userId)).isEmpty();
    }

    // === helpers ===

    /**
     * {@code @Repository}가 예외 변환 프록시로 감싸질 수 있다. 감싸지 않았다면 이 호출은 참조를
     * 그대로 돌려준다.
     */
    private RefreshTokenRepository spiedRepository() {
        return AopTestUtils.getUltimateTargetObject(refreshTokenRepository);
    }

    private TokenCollectionDto login(long id) {
        return refreshTokenService.issue(id, SocialPlatform.KAKAO);
    }

    private RefreshTokenPayload payloadOf(TokenCollectionDto tokens) {
        return jwtTokenProvider.parseRefreshToken(tokens.refreshToken());
    }

    /** {@code @Where(is_deleted = false)}를 우회해야 하므로 네이티브로 묻는다. */
    private boolean softDeleted(long id) {
        return !readCommittedRows(
                "SELECT id FROM users WHERE id = ? AND is_deleted = true", id).isEmpty();
    }

    /** 재가입의 관측 가능한 형태 — 같은 {@code users} 행이 되살아난다. */
    private void reactivate(long id) {
        jdbcTemplate.update(
                "UPDATE users SET is_deleted = false, deleted_at = NULL WHERE id = ?", id);
    }

    private Object revokedAt(TokenCollectionDto tokens) {
        List<Map<String, Object>> rows = readCommittedRows(
                "SELECT revoked_at FROM refresh_token WHERE jwt_id = ?", payloadOf(tokens).jwtId());
        assertThat(rows).hasSize(1);
        return rows.getFirst().get("revoked_at");
    }

    private List<Map<String, Object>> refreshRowsOf(long id) {
        return readCommittedRows(
                "SELECT * FROM refresh_token WHERE user_id = ? ORDER BY id", id);
    }

    private long createUser() {
        // users.nickname은 VARCHAR(30)이다 — UUID를 통째로 붙이면 41자가 되어 INSERT가 죽는다
        String nickname = "탈퇴IT유저" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
        createdUserIds.add(id);
        return id;
    }
}
