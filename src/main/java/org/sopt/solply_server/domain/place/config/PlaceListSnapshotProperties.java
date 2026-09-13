package org.sopt.solply_server.domain.place.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 목록 스냅샷 리빌드의 박자.
 *
 * <p><b>기본값의 근거는 한 가지 실측과 한 가지 예산이다.</b> 기존 x10 데이터셋 63,200 장소를
 * 2 vCPU / Xmx1280MiB에서 <b>무부하로</b> 다시 지었을 때 중앙값 185ms였고, 목록 요청이 스냅샷을
 * 기다릴 수 있는 시간은 3초로 잡혀 있다. 무부하 측정이라 운영 SLA도, 인스턴스 여럿이 동시에
 * 리빌드할 때의 안전성도 이 숫자로는 말할 수 없다 — 초기값이지 검증된 상한이 아니다.
 */
@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "solply.place-list-snapshot")
public class PlaceListSnapshotProperties {

    /**
     * 번호를 보러 가는 간격. 여기서 실제로 나가는 것은 단일 행 PK 조회 하나뿐이라, 반영 지연을
     * 줄이는 값을 이쪽에 몰아 둔다. 무거운 쪽(리빌드)의 빈도는 {@link #minRebuildIntervalMs}가
     * 따로 정한다 — 둘을 한 값으로 묶으면 "자주 보되 드물게 짓는다"를 표현할 수 없다.
     */
    @Positive
    private long pollIntervalMs = 1_000L;

    /**
     * 직전 리빌드가 <b>끝난 뒤</b> 다음 리빌드까지 최소로 쉬는 시간. 185ms짜리 작업이라도 쉼 없이
     * 이어 돌면 읽기 부하가 계속 깔린다.
     *
     * <p><b>목표 cursorVersion을 기다리는 요청은 이 간격을 우회한다.</b> 그 요청은 "쉬어도 된다"가
     * 아니라 "지금 그 회차가 없으면 응답할 수 없다"이기 때문이다. 우회가 남용되지 않는 근거는
     * 대기표가 실제로 있을 때만 우회한다는 것 하나다.
     */
    @Positive
    private long minRebuildIntervalMs = 5_000L;

    /**
     * 목록 요청이 리빌드를 기다리는 최대 시간. 넘기면 {@code PLACE_SNAPSHOT_SYNCING}으로 끊는다 —
     * 낡은 회차로 조용히 답하지 않는다.
     */
    @Positive
    private long requestWaitTimeoutMs = 3_000L;

    /**
     * 기동이 첫 스냅샷을 <b>더 시도하지 않기로 하는 기한</b>. 넘기면 컨텍스트 기동이 실패한다 —
     * 빈 목록을 정상 응답으로 내보내는 인스턴스를 띄우지 않는다.
     *
     * <p><b>한 번의 시도를 끊는 상한이 아니다.</b> 기동 리빌드는 부르는 스레드에서 동기로 돌므로,
     * DB 읽기 하나가 이 값을 넘겨 매달려도 여기서 중단시키지 못한다. 이 값이 정하는 것은
     * "실패한 시도를 몇 번까지 다시 해 보는가"의 기한뿐이고, 개별 DB 호출의 상한은 커넥션·쿼리
     * 타임아웃이 정한다.
     */
    @Positive
    private long bootstrapTimeoutMs = 60_000L;

    /** 리빌드가 실패했을 때 처음 쉬는 시간. 연속 실패마다 두 배로 늘어난다. */
    @Positive
    private long failureBackoffMs = 5_000L;

    /** 실패 백오프의 상한. */
    @Positive
    private long maxFailureBackoffMs = 300_000L;

    /** 연속 실패가 이 횟수에 닿으면 WARN이 아니라 ERROR로 남긴다. */
    @Positive
    private int failureAlertThreshold = 5;
}
