package org.sopt.solply_server.domain.place.service.facade;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인기순 복합 점수 배치의 스케줄 진입점.
 *
 * <p>매일 02:00 전량 재계산. 장소 임베딩(03:00)·코스 임베딩(04:00)과 시간대를 분리한다.
 * 반감기 90일에서 한 시간의 감쇠 변화는 {@code 1 - 0.5^(1/2160) = 0.032%}라 더 잦은 주기는
 * 의미가 없고, 하루 동안 반영되지 않는 것은 상위권 순위를 흔들지 못하는 하루치 유입뿐이다.
 *
 * <p>멱등하므로 여러 인스턴스가 동시에 실행해도 결과가 같다 — 리더 선출(ShedLock 등)이 없다.
 *
 * <p><b>이 클래스에 {@code @Transactional}을 붙이지 말 것.</b> 트랜잭션 경계는
 * {@link PlaceStatsBatchProcessor}가 갖는다. 여기서 트랜잭션을 열면 프로세서가 그것에 참여해
 * {@code READ_COMMITTED} 지정이 조용히 무시되고, 배치가 {@code bookmarks} 전 행에 락을 건다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceStatsFacade {

    private final PlaceStatsBatchProcessor batchProcessor;

    @Scheduled(cron = "${solply.place-stats.cron:0 0 2 * * *}")
    public void recalculatePlaceStats() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();
        try {
            // affectedRows는 장소 수가 아니다 — MySQL이 INSERT를 1, UPDATE를 2로 세므로
            // 정상 운영(전부 UPDATE) 상태에서는 장소 수의 약 2배가 찍힌다. 장소 수로 오해하지 말 것.
            int affected = batchProcessor.recalculateAll(calculatedAt);
            log.info("인기순 점수 배치 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms",
                    calculatedAt, affected,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        } catch (Exception e) {
            // 전량 재계산이라 다음 회차가 전부 복원한다. 스케줄러 스레드로 예외를 흘리지 않는다.
            log.error("인기순 점수 배치 실패 - calculatedAt={}", calculatedAt, e);
        }
    }
}
