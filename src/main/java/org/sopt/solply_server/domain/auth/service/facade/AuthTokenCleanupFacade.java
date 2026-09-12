package org.sopt.solply_server.domain.auth.service.facade;

import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.sopt.solply_server.domain.auth.config.AuthProperties;
import org.sopt.solply_server.domain.auth.service.AuthTokenCleanupProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인증 보존 정리 — 매일 04:40 (KST).
 *
 * <p>지우는 대상은 셋이다: 보존 기간이 지난 refresh 계열, 만료된 어드민 state, 만료된 어드민
 * 교환 코드. Redis에서는 TTL이 하던 일이라 코드가 없었는데, MySQL에는 만료라는 개념이 없으므로
 * 그 일을 회차가 대신한다.
 *
 * <p><b>04:40인 이유는 다른 회차와 시각을 가르기 위해서다.</b> 01:00 인기 점수, 01:45 카운트
 * 안전망, 03:00 장소 임베딩, 04:00 코스 임베딩, 그리고 매시 :15·:30 회차가 이미 있다.
 * {@code @Scheduled} 기본 실행기는 단일 스레드라 겹치면 한쪽이 밀린다.
 *
 * <p>{@code lockAtMostFor PT15M} — 락 보유 인스턴스가 죽었을 때의 자동 해제 상한이고,
 * 다음 회차 간격(24시간)보다 훨씬 짧아 페일오버가 성립한다. 덩어리 크기와 최대 덩어리 수를
 * 올릴 때 이 상한을 함께 볼 것.
 *
 * <p><b>트랜잭션 밖이다.</b> 덩어리마다 프로세서가 자기 트랜잭션을 열고 닫으므로, 한 덩어리가
 * 실패해도 앞서 지운 것은 이미 커밋돼 있고 다음 회차가 남은 것을 이어서 지운다. 예외를
 * 스케줄러 스레드로 흘리지 않는 것도 같은 이유다 — 단일 스레드라 이후 회차의 등록에까지 영향을
 * 준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthTokenCleanupFacade {

    private final AuthTokenCleanupProcessor processor;
    private final AuthProperties authProperties;
    private final Clock clock;

    @Scheduled(cron = "${solply.auth.cleanup-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "auth-token-cleanup", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void cleanupExpiredAuthData() {
        Instant now = clock.instant();
        long startedAt = System.nanoTime();
        // 시작 로그가 없으면 "도는 중"과 "스케줄이 애초에 등록되지 않은 상태"를 구분할 수 없다.
        // 락을 못 잡은 인스턴스는 블록 없이 회차를 건너뛰므로 이 줄은 클러스터 전체에서 한 번이다.
        log.info("인증 보존 정리 배치 시작 - now={}", now);

        int batchSize = authProperties.getCleanupBatchSize();
        long refreshCutoff = now.minus(authProperties.getRefreshRetention()).getEpochSecond();

        int refreshDeleted = runInBatches("refresh 계열 정리",
                () -> processor.deleteExpiredRefreshFamilyBatch(refreshCutoff, batchSize), batchSize);
        int stateDeleted = runInBatches("어드민 OAuth state 정리",
                () -> processor.deleteExpiredAdminStateBatch(now.toEpochMilli(), batchSize), batchSize);
        int authCodeDeleted = runInBatches("어드민 교환 코드 정리",
                () -> processor.deleteExpiredAdminAuthCodeBatch(now.toEpochMilli(), batchSize), batchSize);

        log.info("인증 보존 정리 배치 완료 - refresh={}, state={}, authCode={}, 소요={}ms",
                refreshDeleted, stateDeleted, authCodeDeleted, elapsedMillis(startedAt));
    }

    /**
     * 한 대상을 덩어리로 나눠 지운다. 마지막 덩어리가 가득 차지 않으면 더 지울 것이 없다는 뜻이다.
     *
     * <p>{@code cleanup-max-batches}는 안전망이다. 예상 밖으로 쌓였을 때 회차가 끝나기는 하게
     * 만들고(락 상한 안에 들어온다), 남은 것은 다음 회차가 가져간다. 상한에 닿았다는 사실 자체가
     * 신호이므로 경고로 남긴다.
     */
    private int runInBatches(String label, IntSupplier batch, int batchSize) {
        int total = 0;
        try {
            for (int attempt = 0; attempt < authProperties.getCleanupMaxBatches(); attempt++) {
                int deleted = batch.getAsInt();
                total += deleted;
                if (deleted < batchSize) {
                    return total;
                }
            }
            log.warn("{} - 덩어리 상한({})에 닿았다. 남은 행은 다음 회차가 지운다. 지운 행={}",
                    label, authProperties.getCleanupMaxBatches(), total);
        } catch (Exception e) {
            // 한 대상의 실패가 나머지 대상까지 건너뛰게 하지 않는다. 셋은 서로 독립이다.
            log.error("{} 실패 - 지운 행={}", label, total, e);
        }
        return total;
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
