package org.sopt.solply_server.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트가 손으로 돌리는 시계.
 *
 * <p>refresh 회전의 판정은 전부 시각 비교다 — 만료, 3초 유예의 시작과 끝, 보존 기간. 실제
 * 시간으로 그 경계를 재현하려면 {@code Thread.sleep}으로 기다려야 하고, 그러면 "3초를 넘겼다"와
 * "테스트가 느렸다"가 구분되지 않는다. 여기서는 {@code 유예 종료 1ms 전}과 {@code 정확히 종료
 * 시각}을 각각 찍어 볼 수 있다.
 *
 * <p>{@code ClockConfig}의 빈 대신 이 시계를 주입하는 자리는 각 IT의 {@code @TestConfiguration}이다.
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock fixedUtc(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void setTo(Instant newInstant) {
        this.instant = newInstant;
    }

    public void advance(Duration amount) {
        this.instant = this.instant.plus(amount);
    }

    public void advanceMillis(long millis) {
        this.instant = this.instant.plusMillis(millis);
    }
}
