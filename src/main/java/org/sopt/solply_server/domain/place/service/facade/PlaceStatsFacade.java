package org.sopt.solply_server.domain.place.service.facade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 인기순 복합 점수 배치의 진입점. 정기 스케줄(매시 30분)과 부팅 시 최초 적재 둘을 연다.
 *
 * <p><b>매시 30분 전량 재계산 (2026-08-02 결정. 그전에는 매일 02:00이었다).</b>
 * 잦게 도는 이유는 점수의 신선도가 아니다 — 반감기 90일에서 한 시간의 감쇠 변화는
 * {@code 1 - 0.5^(1/2160) = 0.032%}라 순위를 흔들지 못한다. 이유는 <b>배치 간격이
 * 다른 두 가지의 상한이기 때문</b>이다:
 * <ol>
 *   <li><b>stale 상한</b> — {@code place_stats.town_id}는 places에서 비정규화해 온 값이라
 *       동네를 옮긴 장소가 다음 배치까지 이전 동네 목록에 낀다. 신규 장소가 인기순에서
 *       빠지는 창, 비활성화·재활성화가 반영되지 않는 창도 같다. 쿼리로는 못 막고 배치 간격이
 *       유일한 조절 나사다 — 24h → 1h.</li>
 *   <li><b>표시 카운트의 신선도</b> — 증분 폐지(설계 §5)로 카운트도 배치 전용이 됐다.
 *       방금 누른 북마크가 <em>수</em>에 반영되는 지연이 곧 이 간격이다.</li>
 * </ol>
 * 비용은 무시할 수준이다: 배치 소요 6초, 소스 테이블 락 0건(READ COMMITTED + 시각 상한.
 * 근거는 {@code PlaceStatsRepository#upsertAll} javadoc). 시간당 1회면 하루 24회 × 6초다.
 *
 * <p><b>정각이 아니라 30분인 이유</b>는 장소 임베딩(03:00)·코스 임베딩(04:00)과의 스케줄러
 * 스레드 경합 회피다. {@code @Scheduled} 기본 실행기는 단일 스레드라 정각에 겹치면 한쪽이 밀린다.
 *
 * <p><b>남은 미검증 항목 하나.</b> 매시 배치는 커서 세대가 갈리는 창을 새벽에서 전 시간대로
 * 퍼뜨린다. 1시간치 점수 드리프트가 미미해 페이지 간 항목 흘림·중복은 수용 가능하다고 봤지만,
 * <b>피크 트래픽 중 배치가 도는 순간의 부하는 측정한 적이 없다</b>. 리팩터링 후 측정 캠페인에서
 * "부하 라운드 중 배치 강제 실행 1회"로 확인할 것 (perf 문서 §9-3 후속).
 *
 * <p><b>리더 선출(ShedLock)을 넣은 이유 — "동시 실행이 위험해서"가 아니다.</b> 동시 실행 자체는
 * 안전하다. 각 회차가 <b>완결된 스냅샷을 단일 트랜잭션으로 원자 교체</b>하므로 마지막 커밋이 이기고,
 * 반쯤 섞인 상태는 존재하지 않는다(어느 회차가 이겨도 그 자체로 정합적이다). 다만 그것은 <b>사고로
 * 겹쳤을 때 깨지지 않는다</b>는 성질이지 계획적 중복의 정당화가 아니다 — 매시 배치로 바꾸면서
 * 중복 비용이 인스턴스 2대 기준 하루 48회 전량 스캔이 됐고, 인스턴스 수에 비례해 커진다.
 * 그래서 회차당 한 인스턴스만 돌게 잘랐다 (2026-08-02 결정).
 * <b>결과가 같아서 잘라도 된다는 뜻이 아니다</b> — 두 인스턴스는 각자 {@code LocalDateTime.now()}를
 * 쓰므로 {@code calculatedAt}이 다르고, 1초 차이만으로도 {@code DECIMAL(18,6)}의 6번째 소수
 * 자리에서 값이 갈린다(실측: {@code 02:00:00 → 313.861182}, {@code 02:00:01 → 313.861154}).
 * ("멱등이니 병렬로 쪼개도 된다"로 확장하지 말 것. 쪼개면 부분 반영 상태가 생겨 원자 교체가 깨진다.)
 *
 * <p>실측(4세션 × 60회 = 240회 동시 UPSERT): 오류 0건, {@code Innodb_deadlocks} 0.
 * 두 문장이 동일 플랜·동일 순서로 {@code place_stats} PK를 잠그므로 데드락이 나지 않고,
 * 뒤에 온 세션은 집계를 끝낸 뒤 <b>첫 행에서 앞 배치가 커밋될 때까지 블록</b>됐다.
 * {@code innodb_lock_wait_timeout}(기본 50초)을 넘기면 {@code ERROR 1205}로 죽어 아래
 * {@code log.error}에 삼켜지는 경로였는데, 락을 넣은 지금은 두 번째 인스턴스가 애초에 배치를
 * 시작하지 않으므로 이 대기 자체가 사라졌다. (락이 보호하는 것은 정합성이 아니라 이 낭비다.)
 *
 * <p><b>이 클래스에 {@code @Transactional}을 붙이지 말 것 — Processor를 분리한 핵심 이유다.</b>
 * {@code try/catch}가 트랜잭션 경계 <b>바깥</b>에 있어야 한다. 한 메서드로 합치면 예외를 잡는
 * 지점이 트랜잭션 안이 되어, 실패한 문장 뒤에 커밋을 시도하는 모양이 된다.
 * (참고: {@code @Scheduled} 메서드에 {@code @Transactional}을 직접 붙여도 RC 자체는 정상
 * 적용된다 — {@code ScheduledAnnotationBeanPostProcessor}가 {@code LOWEST_PRECEDENCE}라
 * auto-proxy creator보다 나중에 돌아 프록시를 등록한다. 즉 "합치면 RC가 무시된다"는 이유가
 * 아니다. 합치면 안 되는 이유는 오직 위의 예외 처리 위치다.)
 *
 * <p><b>{@code @SchedulerLock}은 이 불변식과 충돌하지 않는다.</b> ShedLock의 락 획득·해제는
 * {@code JdbcTemplateLockProvider}가 자체의 짧은 트랜잭션으로 수행하고, 이 메서드 본문을
 * 트랜잭션으로 감싸지 않는다. 그러므로 프로세서는 여전히 <b>자기 트랜잭션</b>을 열고
 * READ_COMMITTED가 실제로 적용된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceStatsFacade {

    private final PlaceStatsBatchProcessor batchProcessor;

    /**
     * {@code lockAtMostFor PT10M} — 락 보유 인스턴스가 죽었을 때의 자동 해제 상한. 배치 실측
     * 6초의 100배 여유다. 다음 회차 간격(60분)보다 짧아야 페일오버가 성립한다 — 길게 잡으면
     * 죽은 인스턴스의 락이 다음 회차까지 살아 배치가 통째로 건너뛰어진다.
     *
     * <p>{@code lockAtLeastFor PT1M} — 배치가 6초 만에 끝나도 1분간은 락을 유지한다.
     * 인스턴스 간 발화 시각이 수 초 어긋나도 뒤늦게 깨어난 쪽이 "이미 풀린 락"을 잡아
     * 같은 회차를 다시 돌리지 않게 하는 장치다.
     */
    @Scheduled(cron = "${solply.place-stats.cron:0 30 * * * *}")
    @SchedulerLock(name = "place-stats-recalculate", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recalculatePlaceStats() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();
        // 시작 로그가 없으면 "배치가 도는 중"과 "스케줄이 애초에 안 돌은 상태"를 로그로 구분할 수
        // 없다. 다중 인스턴스에서 락을 못 잡은 쪽은 블록 없이 회차를 건너뛰므로 이 줄도 남기지
        // 않는다 — 즉 회차마다 이 줄은 클러스터 전체에서 정확히 한 번 찍힌다.
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
     * 수단이 위 스케줄뿐이라, 배포 시각부터 다음 회차까지(매시 배치라 최악 1시간) 인기순이
     * 통째로 비고 최신순의 북마크 수가 0으로 응답된다.
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
            // 기동을 막지 않는다. 실패하면 다음 정기 스케줄이 메우고, 그때까지는
            // 통계 없는 장소 분기(0점·0건)로 동작한다 — 응답이 틀릴 뿐 장애는 아니다.
            log.error("인기순 점수 최초 적재 실패 - calculatedAt={}", calculatedAt, e);
        }
    }
}
