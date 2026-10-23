package org.sopt.solply_server.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * {@code ClockConfig}의 {@code Clock.systemUTC()} 대신 {@link MutableClock}을 주입한다.
 *
 * <p>{@code @Primary}로 이기는 것이지 원래 빈을 지우는 것이 아니다 — 프로덕션 설정을 테스트용
 * 분기로 오염시키지 않고 주입 지점만 갈아 끼운다. {@code JwtTokenProvider}·
 * {@code RefreshTokenService}·{@code AdminAuthService}·{@code UserWithdrawService}가 모두 이
 * 시계를 받으므로, 토큰의 {@code iat}/{@code exp}와 행의 회전·유예·폐기 시각이 <b>같은 시계</b>
 * 위에서 움직인다. 경계 단언이 성립하는 근거가 그것이다.
 */
@TestConfiguration
public class MutableClockConfig {

    /** 임의의 고정 시각. 값 자체에는 뜻이 없고, 테스트가 여기서 출발해 손으로 민다. */
    public static final Instant START = Instant.parse("2026-09-12T03:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return MutableClock.fixedUtc(START);
    }
}
