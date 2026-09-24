package org.sopt.solply_server.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.metrics.PlaceListMeters;

/**
 * 계측이 검증 대상이 아닌 단위 테스트가 쓰는 계측 입구. 값을 읽고 싶으면 {@link #registry()}로
 * 만든 레지스트리를 직접 들고 {@link #on(MeterRegistry)}에 넘긴다.
 */
public final class TestMeters {

    private TestMeters() {
    }

    /** 아무도 읽지 않는 계측 입구 — 생성자 인자를 채우기만 한다. */
    public static PlaceListMeters noop() {
        return on(new SimpleMeterRegistry());
    }

    /** 값을 읽을 레지스트리를 직접 쥐여 준다. */
    public static PlaceListMeters on(MeterRegistry registry) {
        return new PlaceListMeters(registry, new PlaceListProperties());
    }
}
