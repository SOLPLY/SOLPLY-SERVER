package org.sopt.solply_server.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상태 판정의 <b>우선순위와 경계</b>. 시각 비교뿐이라 단위 테스트이고, 그래서 경계를 1초·1ms
 * 단위로 겨눌 수 있다 — 실제 시간으로는 "정확히 그 순간"을 찍을 수 없다.
 *
 * <p><b>판정 순서가 곧 의미다.</b> 폐기가 만료보다 앞서는 것은 둘이 다른 사건이기 때문이다 —
 * 전자는 재사용 판정의 근거이고 후자는 그냥 재로그인이다. 순서를 뒤집으면 만료된 폐기 토큰이
 * "그냥 만료"로 읽혀 재사용 감지가 조용히 빠진다.
 */
class RefreshTokenRowStateTest {

    private static final long NOW_SECONDS = 1_789_000_000L;
    private static final Instant NOW = Instant.ofEpochSecond(NOW_SECONDS);

    @Test
    @DisplayName("폐기는 만료보다 앞선다 — 둘 다 해당해도 REVOKED다")
    void revokedWinsOverExpired() {
        RefreshTokenRow row = row(NOW_SECONDS - 1, null, null, NOW.toEpochMilli() - 1);

        assertThat(row.state(NOW)).isEqualTo(RefreshTokenState.REVOKED);
    }

    @Test
    @DisplayName("폐기는 유예보다도 앞선다 — 회전된 부모를 폐기하면 유예가 남아도 REVOKED다")
    void revokedWinsOverGrace() {
        long graceEnd = NOW.toEpochMilli() + 3_000;
        RefreshTokenRow row = row(NOW_SECONDS + 3_600, NOW.toEpochMilli(), graceEnd,
                NOW.toEpochMilli());

        assertThat(row.state(NOW)).isEqualTo(RefreshTokenState.REVOKED);
    }

    /** {@code expires_at <= now}가 만료의 정의다 — <b>정각은 이미 만료다.</b> */
    @Test
    void 만료_경계는_정각에_이미_지난_것으로_본다() {
        assertThat(row(NOW_SECONDS + 1, null, null, null).state(NOW))
                .isEqualTo(RefreshTokenState.ACTIVE);
        assertThat(row(NOW_SECONDS, null, null, null).state(NOW))
                .isEqualTo(RefreshTokenState.EXPIRED);
        assertThat(row(NOW_SECONDS - 1, null, null, null).state(NOW))
                .isEqualTo(RefreshTokenState.EXPIRED);
    }

    /** 만료는 회전 여부보다 앞선다 — 회전된 부모여도 만료됐으면 재사용 판정이 아니다. */
    @Test
    void 만료는_유예보다_앞선다() {
        RefreshTokenRow row = row(NOW_SECONDS - 1, NOW.toEpochMilli() - 10,
                NOW.toEpochMilli() + 3_000, null);

        assertThat(row.state(NOW)).isEqualTo(RefreshTokenState.EXPIRED);
    }

    /**
     * <b>{@code now < grace_expires_at}이 유예의 정의다.</b> 종료 정각은 이미 유예가 끝난 것이고,
     * 그 1ms 차이에서 판정이 "같은 토큰을 한 번 더 준다"와 "계정 전체를 끊는다"로 갈린다.
     */
    @Test
    void 유예_경계는_종료_정각에_이미_끝난_것으로_본다() {
        long graceEnd = NOW.toEpochMilli() + 3_000;

        assertThat(rotated(graceEnd).state(NOW.minusMillis(1))).isEqualTo(RefreshTokenState.GRACE);
        assertThat(rotated(graceEnd).state(Instant.ofEpochMilli(graceEnd - 1)))
                .isEqualTo(RefreshTokenState.GRACE);
        assertThat(rotated(graceEnd).state(Instant.ofEpochMilli(graceEnd)))
                .isEqualTo(RefreshTokenState.GRACE_ENDED);
        assertThat(rotated(graceEnd).state(Instant.ofEpochMilli(graceEnd + 1)))
                .isEqualTo(RefreshTokenState.GRACE_ENDED);
    }

    @Test
    void 미회전_살아있는_토큰만_ACTIVE다() {
        assertThat(row(NOW_SECONDS + 3_600, null, null, null).state(NOW))
                .isEqualTo(RefreshTokenState.ACTIVE);
    }

    /**
     * 회전 시각과 유예 종료 시각은 한 문장에서 함께 쓰인다 — 한쪽만 채워진 행은 있을 수 없고,
     * 그런 행을 만나면 재사용으로 단정하지 않는다. 그 판정의 근거가 이 메서드다.
     */
    @Test
    void 한쪽만_채워진_시각은_정합성_오류다() {
        assertThat(row(NOW_SECONDS + 10, null, null, null).isConsistent()).isTrue();
        assertThat(row(NOW_SECONDS + 10, 1L, 2L, null).isConsistent()).isTrue();
        assertThat(row(NOW_SECONDS + 10, 1L, null, null).isConsistent()).isFalse();
        assertThat(row(NOW_SECONDS + 10, null, 2L, null).isConsistent()).isFalse();
    }

    private static RefreshTokenRow rotated(long graceExpiresAtMillis) {
        return row(NOW_SECONDS + 86_400, graceExpiresAtMillis - 3_000, graceExpiresAtMillis, null);
    }

    private static RefreshTokenRow row(long expiresAtSeconds, Long rotatedAtMillis,
            Long graceExpiresAtMillis, Long revokedAtMillis) {
        return new RefreshTokenRow(1L, 7L, "fam", "jti", null, SocialPlatform.KAKAO, 1,
                expiresAtSeconds - 3_600, expiresAtSeconds,
                rotatedAtMillis, graceExpiresAtMillis, revokedAtMillis);
    }
}
