package org.sopt.solply_server.domain.place.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 목록 스냅샷 갱신 폴과 알림 구독의 설정.
 *
 * <p>application.yml은 {@code .gitignore}에 걸려 커밋되지 않으므로 아래 기본값이 저장소에 남는
 * 유일한 선언이다 — 사정은 {@link PlaceStatsProperties}와 같다.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "solply.place-list-snapshot")
public class PlaceListSnapshotProperties {

    /**
     * 발행자가 밀린 재빌드 요청을 보러 가는 간격. 통계 커밋이 발행물에 닿기까지 이만큼 밀린다.
     *
     * <p>요청이 없으면 그 폴은 발행물 행을 읽지도 않는다.
     */
    @Positive
    private long publishPollIntervalMs = 5_000L;

    /**
     * 각 인스턴스가 발행 포인터를 보러 가는 간격. <b>인스턴스 사이의 교체 시차가 이 값이다</b> —
     * 새 발행이 난 뒤 최대 이만큼 인스턴스마다 다른 회차를 서빙한다.
     *
     * <p>포인터가 그대로면 payload를 읽지 않는다.
     */
    @Positive
    private long adoptPollIntervalMs = 5_000L;

    /**
     * 아직 아무도 발행하지 않은 상태로 기동했을 때 발행물을 기다리는 상한. 넘기면 기동을 접는다 —
     * <b>빈 스냅샷으로 트래픽을 받지 않는 것</b>이 그 판단의 근거다.
     */
    @Positive
    private long bootstrapTimeoutMs = 60_000L;
}
