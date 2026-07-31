package org.sopt.solply_server.domain.place.service.facade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인기순 복합 점수 배치의 진입점. 정기 스케줄(매일 02:00)과 부팅 시 최초 적재 둘을 연다.
 *
 * <p>매일 02:00 전량 재계산. 장소 임베딩(03:00)·코스 임베딩(04:00)과 시간대를 분리한다.
 * 반감기 90일에서 한 시간의 감쇠 변화는 {@code 1 - 0.5^(1/2160) = 0.032%}라 더 잦은 주기는
 * 의미가 없고, 하루 동안 반영되지 않는 것은 상위권 순위를 흔들지 못하는 하루치 유입뿐이다.
 *
 * <p><b>리더 선출(ShedLock 등)이 없는 이유 — "멱등해서"가 아니다.</b> 두 인스턴스는 각자
 * {@code LocalDateTime.now()}를 쓰므로 {@code calculatedAt}이 다르고, 1초 차이만으로도
 * {@code DECIMAL(18,6)}의 6번째 소수 자리에서 값이 갈린다(실측: {@code 02:00:00 → 313.861182},
 * {@code 02:00:01 → 313.861154}). 즉 <b>동시 실행 결과는 같지 않다.</b>
 * 안전한 진짜 이유는 각 회차가 <b>완결된 스냅샷을 단일 트랜잭션으로 원자 교체</b>하기 때문이다 —
 * 마지막 커밋이 이기고, 반쯤 섞인 상태는 존재하지 않는다. 어느 세대가 이겨도 그 자체로 정합적이다.
 * ("멱등이니 병렬로 쪼개도 된다"로 확장하지 말 것. 쪼개면 부분 반영 상태가 생겨 이 성질이 깨진다.)
 *
 * <p>실측(4세션 × 60회 = 240회 동시 UPSERT): 오류 0건, {@code Innodb_deadlocks} 0.
 * 두 문장이 동일 플랜·동일 순서로 {@code place_stats} PK를 잠그므로 데드락이 나지 않고,
 * 뒤에 온 세션은 집계를 끝낸 뒤 <b>첫 행에서 앞 배치가 커밋될 때까지 블록</b>된다.
 * 따라서 배치가 {@code innodb_lock_wait_timeout}(기본 50초)을 넘기면 두 번째 인스턴스는
 * {@code ERROR 1205}로 죽고 아래 {@code log.error}에 삼켜진다 — 데이터는 무해하지만
 * <b>매일 에러 로그가 한 줄 남는다.</b> 그때는 리더 선출을 넣을 시점이다.
 *
 * <p><b>이 클래스에 {@code @Transactional}을 붙이지 말 것 — Processor를 분리한 핵심 이유다.</b>
 * {@code try/catch}가 트랜잭션 경계 <b>바깥</b>에 있어야 한다. 한 메서드로 합치면 예외를 잡는
 * 지점이 트랜잭션 안이 되어, 실패한 문장 뒤에 커밋을 시도하는 모양이 된다.
 * (참고: {@code @Scheduled} 메서드에 {@code @Transactional}을 직접 붙여도 RC 자체는 정상
 * 적용된다 — {@code ScheduledAnnotationBeanPostProcessor}가 {@code LOWEST_PRECEDENCE}라
 * auto-proxy creator보다 나중에 돌아 프록시를 등록한다. 즉 "합치면 RC가 무시된다"는 이유가
 * 아니다. 합치면 안 되는 이유는 오직 위의 예외 처리 위치다.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceStatsFacade {

    private final PlaceStatsBatchProcessor batchProcessor;

    @Scheduled(cron = "${solply.place-stats.cron:0 0 2 * * *}")
    public void recalculatePlaceStats() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();
        // 시작 로그가 없으면 "배치가 락 대기로 매달린 상태"와 "스케줄이 애초에 안 돌은 상태"를
        // 로그로 구분할 수 없다. 다중 인스턴스에서 뒤에 온 쪽은 최대 50초 블록될 수 있다.
        log.info("인기순 점수 배치 시작 - calculatedAt={}", calculatedAt);
        try {
            // affectedRows는 장소 수가 아니다 — MySQL이 INSERT를 1, UPDATE를 2로 세므로
            // 정상 운영(전부 UPDATE) 상태에서는 장소 수의 약 2배가 찍힌다. 장소 수로 오해하지 말 것.
            int affected = batchProcessor.recalculateAll(calculatedAt);
            log.info("인기순 점수 배치 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms",
                    calculatedAt, affected,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (Exception e) {
            // 전량 재계산이라 다음 회차가 전부 복원한다. 스케줄러 스레드로 예외를 흘리지 않는다.
            log.error("인기순 점수 배치 실패 - calculatedAt={}", calculatedAt, e);
        }
    }

    /**
     * 부팅 시 {@code place_stats} 최초 적재.
     *
     * <p><b>이 진입점이 없으면 배포 첫날의 읽기 경로가 전부 0이 된다.</b>
     * {@code PlaceStatsRepository.findViewsByPlaceIds}가 이 테이블을 읽는데 {@code V24}는 백필하지 않고 채우는
     * 수단이 위 스케줄뿐이라, 배포 시각부터 다음 02:00까지 최악 24시간 동안 전 장소가
     * "통계 행 없음" 분기를 타 인기순이 장소 id 순서가 되고 북마크 수가 0으로 응답된다.
     *
     * <p><b>Flyway 백필 마이그레이션을 쓰지 않은 이유:</b> Flyway는 자기 트랜잭션(기본 RR)에서
     * 돌아 {@link org.sopt.solply_server.domain.place.repository.PlaceStatsRepository#upsertAll}
     * javadoc이 실측으로 경고한 {@code bookmarks} next-key 락을 그대로 건다. 무중단 배포 중이면
     * 동시 북마크 INSERT가 {@code ERROR 1205}로 죽는다. 이 경로는 이미 검증된 프로세서의
     * READ_COMMITTED 경계를 그대로 재사용한다.
     *
     * <p>실제 실행 여부와 "비었을 때만"의 근거는
     * {@link PlaceStatsBatchProcessor#recalculateIfEmpty}에 있다.
     *
     * <p>여기서도 {@code @Transactional}을 붙이면 안 된다 — 위 클래스 주석과 같은 이유이고,
     * 붙는 순간 프로세서가 이 트랜잭션에 참여해 READ_COMMITTED 지정이 조용히 버려진다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillPlaceStatsOnStartup() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();
        log.info("인기순 점수 최초 적재 검사 - calculatedAt={}", calculatedAt);
        try {
            OptionalInt affected = batchProcessor.recalculateIfEmpty(calculatedAt);
            if (affected.isEmpty()) {
                // 생략 로그는 프로세서가 찍는다 — 기존 행 수를 아는 지점이 거기뿐이고,
                // 여기서 한 줄 더 남기면 같은 사건이 숫자 없는 줄과 겹쳐 두 번 찍힌다.
                return;
            }
            log.info("인기순 점수 최초 적재 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms",
                    calculatedAt, affected.getAsInt(),
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (Exception e) {
            // 기동을 막지 않는다. 실패하면 다음 02:00 스케줄이 메우고, 그때까지는
            // 통계 없는 장소 분기(0점·0건)로 동작한다 — 응답이 틀릴 뿐 장애는 아니다.
            log.error("인기순 점수 최초 적재 실패 - calculatedAt={}", calculatedAt, e);
        }
    }
}
