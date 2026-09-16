package org.sopt.solply_server.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 인증이 읽는 "지금".
 *
 * <p>refresh 회전의 판정은 전부 시각 비교다 — 만료, 3초 유예의 시작과 끝, 보존 기간. 그 경계를
 * 테스트로 재현하려면 시계가 주입 가능해야 하고, 그러려면 코드가 {@code Instant.now()}를 직접
 * 부르지 않아야 한다.
 *
 * <p>{@code systemUTC()}인 이유는 JVM 기본 시간대가 Asia/Seoul이기 때문이다
 * ({@code TimezoneConfig}). 저장하는 시각은 전부 epoch 정수라 시간대가 개입할 자리가 없는데,
 * 시계만 지역 시간대로 두면 로그와 디버깅에서 "어느 쪽이 UTC인가"가 흐려진다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
