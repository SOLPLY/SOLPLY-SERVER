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
 * {@code place_stats} 집계 배치의 진입점. 정기 스케줄 <b>둘</b>과 부팅 시 최초 적재를 연다.
 *
 * <p><b>주기를 가른 이유는 두 값의 신선도 요구가 다르기 때문이다 (2026-08-07 결정).</b>
 * <ol>
 *   <li><b>카운트 — 매시 30분.</b> 화면에 찍히는 북마크 수·리뷰 수·평점이고, 방금 누른 북마크가
 *       <em>수</em>에 반영되는 지연이 곧 이 간격이다. 여기에는 {@code town_id} 비정규화의 stale
 *       상한도 얹혀 있다 — 동네를 옮긴 장소가 이전 동네 목록에 끼는 창, 신규 장소가 인기순에서
 *       빠지는 창, 비활성화·재활성화가 반영되지 않는 창이 전부 이 간격이다. 쿼리로는 못 막고
 *       배치 간격이 유일한 조절 나사다.</li>
 *   <li><b>인기 점수 — 매일 01:00 (KST).</b> 반감기 90일에서 하루의 감쇠 변화는
 *       {@code 1 - 0.5^(1/90) = 0.77%}라 순위를 흔들지 못한다. 잦게 돌 이유가 없는 대신,
 *       <b>점수가 갈리는 순간이 곧 커서 좌표계가 갈리는 순간</b>이라 그 창을 트래픽 최저 시각의
 *       수 초로 몰아 두는 편익이 크다 — 커서에서 세대 필드를 걷어낼 수 있게 된 근거가 이것이다
 *       ({@code PlaceListCursor}).</li>
 * </ol>
 * 분리가 스캔을 아끼지는 않는다. 카운트도 점수도 {@code bookmarks}·{@code place_reviews} 전량을
 * 훑으므로 오히려 각 원본을 하루에 한 번 더 훑는다. 얻는 것은 비용이 아니라 <b>주기를 따로 잡을
 * 자유</b>이고, 그 대가는 하루 1회의 추가 스캔이다.
 *
 * <p><b>두 배치는 겹치지 않는다.</b> 점수 배치가 {@code place_stats} 전 행에 X 락을 커밋까지 들고
 * 있으므로 같은 시각에 카운트 배치가 돌면 서로를 기다린다. 01:00(점수)과 01:30(카운트)은 실측
 * 배치 소요 6초의 300배 간격이라 구조적으로 만나지 않는다. <b>주기를 바꿀 때 이 간격을 함께
 * 확인할 것.</b> 락 이름을 나눠 둔 것은 그 반대 이유다 — 하나로 묶으면 정시에 겹친 두 회차 중
 * 하나가 통째로 건너뛰어진다.
 *
 * <p><b>정각이 아니라 30분인 이유</b>는 장소 임베딩(03:00)·코스 임베딩(04:00)과의 스케줄러
 * 스레드 경합 회피다. {@code @Scheduled} 기본 실행기는 단일 스레드라 정각에 겹치면 한쪽이 밀린다.
 * 점수 배치의 01:00은 그 둘과 두 시간 이상 떨어져 있어 같은 문제가 없다.
 *
 * <p><b>리더 선출(ShedLock)을 넣은 이유 — "동시 실행이 위험해서"가 아니다.</b> 동시 실행 자체는
 * 안전하다. 각 회차가 <b>완결된 스냅샷을 단일 트랜잭션으로 원자 교체</b>하므로 마지막 커밋이 이기고,
 * 반쯤 섞인 상태는 존재하지 않는다(어느 회차가 이겨도 그 자체로 정합적이다). 다만 그것은 <b>사고로
 * 겹쳤을 때 깨지지 않는다</b>는 성질이지 계획적 중복의 정당화가 아니다 — 중복 비용이 인스턴스
 * 2대 기준 하루 48회 전량 스캔이 되고, 인스턴스 수에 비례해 커진다. 그래서 회차당 한 인스턴스만
 * 돌게 잘랐다 (2026-08-02 결정).
 * <b>결과가 같아서 잘라도 된다는 뜻이 아니다</b> — 두 인스턴스는 각자 {@code LocalDateTime.now()}를
 * 쓰므로 {@code calculatedAt}이 다르고, 1초 차이만으로도 {@code DECIMAL(18,6)}의 6번째 소수
 * 자리에서 값이 갈린다(실측: {@code 02:00:00 → 313.861182}, {@code 02:00:01 → 313.861154}).
 * ("멱등이니 병렬로 쪼개도 된다"로 확장하지 말 것. 쪼개면 부분 반영 상태가 생겨 원자 교체가 깨진다.)
 *
 * <p>실측(4세션 × 60회 = 240회 동시 UPSERT): 오류 0건, {@code Innodb_deadlocks} 0.
 * 두 세션이 동일 플랜·동일 순서로 {@code place_stats} PK를 잠그므로 데드락이 나지 않고,
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
     * 표시 카운트 회차 — 매시 30분 (KST).
     *
     * <p><b>매시 배치에 {@code zone}이 필요한가 — 발화 시각만 보면 아니다.</b> 시간대가 무엇이든
     * 매시 30분은 매시 30분이다(30분 단위 오프셋을 쓰는 지역이 아닌 한). 그럼에도 명시하는 이유는
     * <b>두 회차의 시간대 계약을 한 줄로 읽히게 하기 위해서다</b> — 한쪽만 {@code zone}을 달아 두면
     * 다음 사람이 "카운트는 서버 시간대, 점수는 KST"라는 있지도 않은 비대칭을 읽는다. 실제 계약은
     * "두 회차 모두 KST 벽시계"이고, 아래 30분 간격이 그 위에서만 성립한다.
     *
     * <p>{@code lockAtMostFor PT10M} — 락 보유 인스턴스가 죽었을 때의 자동 해제 상한. 배치 실측
     * 6초의 100배 여유다. 다음 회차 간격(60분)보다 짧아야 페일오버가 성립한다 — 길게 잡으면
     * 죽은 인스턴스의 락이 다음 회차까지 살아 배치가 통째로 건너뛰어진다.
     *
     * <p>{@code lockAtLeastFor PT1M} — 배치가 6초 만에 끝나도 1분간은 락을 유지한다.
     * 인스턴스 간 발화 시각이 수 초 어긋나도 뒤늦게 깨어난 쪽이 "이미 풀린 락"을 잡아
     * 같은 회차를 다시 돌리지 않게 하는 장치다.
     */
    @Scheduled(cron = "${solply.place-stats.count-cron:0 30 * * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "place-stats-count", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recalculatePlaceCounts() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();
        // 시작 로그가 없으면 "배치가 도는 중"과 "스케줄이 애초에 안 돌은 상태"를 로그로 구분할 수
        // 없다. 다중 인스턴스에서 락을 못 잡은 쪽은 블록 없이 회차를 건너뛰므로 이 줄도 남기지
        // 않는다 — 즉 회차마다 이 줄은 클러스터 전체에서 정확히 한 번 찍힌다.
        log.info("인기순 카운트 배치 시작 - calculatedAt={}", calculatedAt);
        try {
            // affectedRows는 장소 수가 아니다 — MySQL이 INSERT를 1, UPDATE를 2로 세므로
            // 정상 운영(전부 UPDATE) 상태에서는 장소 수의 약 2배가 찍힌다. 장소 수로 오해하지 말 것.
            int affected = batchProcessor.recalculateCounts(calculatedAt);
            log.info("인기순 카운트 배치 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms",
                    calculatedAt, affected,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (Exception e) {
            // 전량 재계산이라 다음 회차가 전부 복원한다. 스케줄러 스레드로 예외를 흘리지 않는다.
            log.error("인기순 카운트 배치 실패 - calculatedAt={}", calculatedAt, e);
        }
    }

    /**
     * 인기 점수 회차 — 매일 01:00 (KST).
     *
     * <p><b>{@code zone}을 명시하는 이유.</b> {@code TimezoneConfig}가 JVM 기본 시간대를 이미
     * Asia/Seoul로 고정하므로 생략해도 지금은 같은 시각에 돈다. 그럼에도 적어 두는 것은 이 배치의
     * 시각이 <b>"트래픽 최저 시각"이라는 이유로 고른 값</b>이기 때문이다 — 그 근거는 서버가 어느
     * 시간대에 뜨느냐와 무관하게 KST에 매여 있다. 카운트 회차도 같은 값을 달아 두 회차의 시간대
     * 계약을 한 벌로 유지한다.
     *
     * <p>{@code lockAtMostFor PT30M} — 카운트 회차보다 넉넉히 잡는다. 회차 간격이 24시간이라
     * 페일오버 상한을 짧게 유지할 이유가 없고, 점수 문장은 {@code place_stats} 전 행을 갱신하므로
     * 데이터가 늘면 카운트보다 먼저 길어진다. {@code lockAtLeastFor}의 근거는 카운트와 같다.
     */
    @Scheduled(cron = "${solply.place-stats.score-cron:0 0 1 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "place-stats-score", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void recalculatePopularScores() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();
        log.info("인기점수 배치 시작 - calculatedAt={}", calculatedAt);
        try {
            int affected = batchProcessor.recalculateScores(calculatedAt);
            log.info("인기점수 배치 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms",
                    calculatedAt, affected,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (Exception e) {
            log.error("인기점수 배치 실패 - calculatedAt={}", calculatedAt, e);
        }
    }

    /**
     * 부팅 시 {@code place_stats} 최초 적재 — <b>카운트 먼저, 점수 다음</b>.
     *
     * <p><b>순서가 계약이다.</b> 점수 문장은 이미 존재하는 행만 갱신하므로, 카운트가 행을 만들기
     * 전에 채점하면 0행을 갱신하고 끝난다.
     *
     * <p><b>이 진입점이 없으면 배포 첫날의 읽기 경로가 전부 0이 된다.</b> 인기순은 place_stats가
     * 기준 테이블이라 통째로 비고, 최신순의 북마크 수가 0으로 응답된다. 마이그레이션(V32)은
     * 테이블을 재생성만 하고 백필하지 않으므로 여기가 유일한 즉시 복구 경로다.
     *
     * <p><b>Flyway 백필 마이그레이션을 쓰지 않은 이유:</b> Flyway는 자기 트랜잭션(기본 RR)에서
     * 돌아 {@link org.sopt.solply_server.domain.place.repository.PlaceStatsRepository#upsertCounts}
     * javadoc이 실측으로 경고한 {@code bookmarks} next-key 락을 그대로 건다. 무중단 배포 중이면
     * 동시 북마크 INSERT가 {@code ERROR 1205}로 죽는다. 이 경로는 이미 검증된 프로세서의
     * READ_COMMITTED 경계를 그대로 재사용한다.
     *
     * <p>두 단계의 실행 조건이 서로 다르다는 것도 중요하다 — 카운트는 "테이블이 비었는가",
     * 점수는 "채점된 행이 하나라도 있는가"를 본다. 카운트 배치가 만든 미채점 행은 행 수로는
     * "채워져 있다"라서, 점수 쪽이 같은 가드를 쓰면 다음 새벽까지 전 장소가 0점이 된다.
     * 근거는 {@link PlaceStatsBatchProcessor#recalculateScoresIfNeverScored}에 있다.
     *
     * <p>여기서도 {@code @Transactional}을 붙이면 안 된다 — 위 클래스 주석과 같은 이유이고,
     * 붙는 순간 프로세서가 이 트랜잭션에 참여해 READ_COMMITTED 지정이 조용히 버려진다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillPlaceStatsOnStartup() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        log.info("인기순 통계 최초 적재 검사 - calculatedAt={}", calculatedAt);
        backfillCounts(calculatedAt);
        backfillScores(calculatedAt);
    }

    /**
     * 두 단계를 각각 감싸는 이유: 카운트가 실패해도 점수 채점은 시도할 값어치가 있고(이전 배포가
     * 남긴 행이 있을 수 있다), 무엇보다 어느 단계가 죽었는지 로그로 갈려야 한다.
     * 기동 자체는 어느 쪽이 실패해도 계속된다 — 여기서 예외가 새면
     * {@code ApplicationReadyEvent} 발행이 실패해 애플리케이션이 뜨지 않는다.
     */
    private void backfillCounts(LocalDateTime calculatedAt) {
        long startNanos = System.nanoTime();
        try {
            OptionalInt affected = batchProcessor.recalculateCountsIfEmpty(calculatedAt);
            if (affected.isEmpty()) {
                // 생략 로그는 프로세서가 찍는다 — 기존 행 수를 아는 지점이 거기뿐이고,
                // 여기서 한 줄 더 남기면 같은 사건이 숫자 없는 줄과 겹쳐 두 번 찍힌다.
                return;
            }
            log.info("인기순 카운트 최초 적재 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms",
                    calculatedAt, affected.getAsInt(),
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (Exception e) {
            // 실패하면 다음 정기 회차가 메우고, 그때까지는 통계 없는 장소 분기(0점·0건)로 동작한다 —
            // 응답이 틀릴 뿐 장애는 아니다.
            log.error("인기순 카운트 최초 적재 실패 - calculatedAt={}", calculatedAt, e);
        }
    }

    private void backfillScores(LocalDateTime calculatedAt) {
        long startNanos = System.nanoTime();
        try {
            OptionalInt affected = batchProcessor.recalculateScoresIfNeverScored(calculatedAt);
            if (affected.isEmpty()) {
                return;
            }
            log.info("인기점수 최초 채점 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms",
                    calculatedAt, affected.getAsInt(),
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (Exception e) {
            log.error("인기점수 최초 채점 실패 - calculatedAt={}", calculatedAt, e);
        }
    }
}
