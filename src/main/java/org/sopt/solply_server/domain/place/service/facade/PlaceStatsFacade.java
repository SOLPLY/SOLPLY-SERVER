package org.sopt.solply_server.domain.place.service.facade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code place_stats} 집계 배치의 진입점. 정기 스케줄 <b>셋</b>과 부팅 시 최초 적재를 연다.
 *
 * <p><b>이 배치는 목록 캐시를 모른다.</b> 여기서 하는 일은 {@code place_stats}를 고치는 것까지이고,
 * 그 값이 목록에 나타나는 것은 다음 스냅샷 회차다 ({@code PlaceListSnapshotScheduler}).
 *
 * <p><b>주기를 가른 이유는 값마다 신선도 요구가 다르기 때문이다 (2026-08-07 결정).</b>
 * <ol>
 *   <li><b>카운트 — 매시 30분.</b> 화면에 찍히는 북마크 수·리뷰 수·평점이고, 방금 누른 북마크가
 *       <em>수</em>에 반영되는 지연이 곧 이 간격이다. <b>이 회차가 만지는 것은 그 세 값뿐이고,
 *       그중에서도 값이 실제로 달라진 행뿐이다</b> (V35) — 파생 컬럼({@code town_id}·
 *       {@code tag_bitmask}·{@code created_at})과 행의 존재 여부는 어드민 쓰기 트랜잭션의 소유라
 *       여기서 손대지 않는다 ({@code AdminPlaceService}).
 *       <b>회차의 구성이 2026-08-17에 둘로 갈렸다</b> — 리뷰 축은 전량 재계산(실측 117ms),
 *       북마크 축은 아웃박스 델타 소비다. 북마크 전량 스캔(1,040만 행, 옛 회차 비용의 98%)이
 *       빠지면서 이 회차의 비용은 총 행 수가 아니라 <b>지난 한 시간의 토글 수</b>에 붙는다.</li>
 *   <li><b>카운트 안전망 — 매일 01:45 (KST).</b> 옛 매시 문장(표시 카운트 셋 전량 재계산)이
 *       내려온 자리다. 델타는 발행·소비·표류 방어의 규칙 위에 서 있으므로, 버그로 어긋난 값의
 *       상한을 하루로 잡는 겹이 하나 필요하다. 근거는
 *       {@link #recalculatePlaceCountsSafety()}.</li>
 *   <li><b>인기 점수 — 매일 01:00 (KST).</b> 반감기 90일에서 하루의 감쇠 변화는
 *       {@code 1 - 0.5^(1/90) = 0.77%}라 순위를 흔들지 못한다. 잦게 돌 이유가 없는 대신,
 *       <b>점수가 갈리는 순간이 곧 커서 좌표계가 갈리는 순간</b>이라 그 창을 트래픽 최저 시각의
 *       수 초로 몰아 두는 편익이 크다 — 커서에서 세대 필드를 걷어낼 수 있게 된 근거가 이것이다
 *       ({@code PlaceListCursor}).</li>
 * </ol>
 * 카운트와 점수의 분리가 스캔을 아끼지는 않는다. 두 회차가 각자 {@code place_reviews}를 훑으므로
 * 오히려 원본을 하루에 한 번 더 훑는다. 얻는 것은 비용이 아니라 <b>주기를 따로 잡을 자유</b>다.
 *
 * <p><b>세 회차는 시각이 갈려 있다.</b> 점수 배치가 {@code place_stats} 전 행에 X 락을 커밋까지
 * 들고 있어 같은 시각에 카운트 쪽이 돌면 서로를 기다리고, 안전망과 매시 회차는 아웃박스 전표를
 * {@code FOR UPDATE}로 잡아 같은 관문에서 직렬화된다. 그래서 01:00(점수) · 01:30(매시 카운트) ·
 * 01:45(안전망)로 15분 이상씩 벌려 뒀다 — 재시도까지 다 쓴 회차의 최장 시간이 약 21초라
 * 40배 이상 여유다(옛 카운트 실측 3.5초 기준이고, 매시 회차는 북마크 전량 스캔을 잃어 그보다
 * 훨씬 짧다). <b>주기나 {@code batch-max-attempts}를 바꿀 때 이 간격을 함께 확인할 것.</b>
 * 락 이름을 셋으로 나눠 둔 것은 그 반대 이유다 — 하나로 묶으면 정시에 겹친 회차 중 하나가
 * 통째로 건너뛰어진다.
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
    /** 매시 회차의 북마크 축. 트랜잭션 경계를 스스로 가지므로 여기서는 부르기만 한다 */
    private final BookmarkCountDeltaProcessor deltaProcessor;
    private final PlaceStatsProperties placeStatsProperties;

    /**
     * 표시 카운트 회차 — 매시 30분 (KST). <b>리뷰 축 전량 재계산 + 북마크 축 델타 소비</b> 둘로
     * 이루어진다.
     *
     * <p><b>매시 배치에 {@code zone}이 필요한가 — 발화 시각만 보면 아니다.</b> 시간대가 무엇이든
     * 매시 30분은 매시 30분이다(30분 단위 오프셋을 쓰는 지역이 아닌 한). 그럼에도 명시하는 이유는
     * <b>두 회차의 시간대 계약을 한 줄로 읽히게 하기 위해서다</b> — 한쪽만 {@code zone}을 달아 두면
     * 다음 사람이 "카운트는 서버 시간대, 점수는 KST"라는 있지도 않은 비대칭을 읽는다. 실제 계약은
     * "두 회차 모두 KST 벽시계"이고, 아래 30분 간격이 그 위에서만 성립한다.
     *
     * <p>{@code lockAtMostFor PT10M} — 락 보유 인스턴스가 죽었을 때의 자동 해제 상한.
     * <b>재시도가 들어온 뒤로는 회차 하나의 최장 시간을 담을 수 있어야 한다</b> —
     * 최대 시도 3회 × 실측 3.5초 + 대기 2회 × 5초 ≈ 21초로 약 29배 여유다
     * (2026-08-15 실측, `docs/perf/2026-08-15-count-batch-duration-lock.md`).
     * 다음 회차 간격(60분)보다는 짧아야 페일오버가 성립한다 — 길게 잡으면 죽은 인스턴스의 락이
     * 다음 회차까지 살아 배치가 통째로 건너뛰어진다.
     * <b>{@code batch-max-attempts}를 올릴 때 이 상한을 함께 볼 것.</b>
     *
     * <p>{@code lockAtLeastFor PT1M} — 배치가 6초 만에 끝나도 1분간은 락을 유지한다.
     * 인스턴스 간 발화 시각이 수 초 어긋나도 뒤늦게 깨어난 쪽이 "이미 풀린 락"을 잡아
     * 같은 회차를 다시 돌리지 않게 하는 장치다.
     */
    @Scheduled(cron = "${solply.place-stats.count-cron:0 30 * * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "place-stats-count", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recalculatePlaceCounts() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        // 시작 로그가 없으면 "배치가 도는 중"과 "스케줄이 애초에 안 돌은 상태"를 로그로 구분할 수
        // 없다. 다중 인스턴스에서 락을 못 잡은 쪽은 블록 없이 회차를 건너뛰므로 이 줄도 남기지
        // 않는다 — 즉 회차마다 이 줄은 클러스터 전체에서 정확히 한 번 찍힌다.
        log.info("인기순 카운트 배치 시작 - calculatedAt={}", calculatedAt);

        // affectedRows는 문장이 걸린 행 수(matched)이고, 순수 UPDATE인 지금은 그것이 곧
        // place_stats 행 수 = 목록 노출 대상 장소 수다. 배치가 실제로 쓴 행 수가 아니다 —
        // 회차 시각 컬럼을 걷어낸 뒤로(V35) 실제 쓰기는 카운트가 달라진 장소로 좁혀졌는데,
        // 이 수치는 그와 무관하게 전 행을 센다. 둘이 갈라진 것이 V35의 실익 그 자체다.
        // UPSERT였던 시절에는 MySQL이 INSERT를 1, UPDATE를 2로 세어 장소 수의 약 2배가
        // 찍혔다 — 옛 로그를 비교할 때 그 차이를 감안할 것.
        runWithRetry("인기순 리뷰 카운트 재계산", calculatedAt,
                () -> batchProcessor.recalculateReviewCounts(calculatedAt));

        // 델타 소비의 affectedRows는 성질이 정반대다 — 장소 수가 아니라 이번 회차가 삼킨
        // 전표 수, 곧 지난 한 시간의 토글 수다. 이 수치가 회차의 비용 그 자체이므로 로그로
        // 남는 값도 그쪽이어야 한다.
        // 재시도가 안전한 근거는 재계산의 멱등성이 아니라 소비의 트랜잭션성이다 — 적용과 삭제가
        // 한 트랜잭션이라 실패한 시도는 전표를 그대로 남기고 롤백된다.
        runWithRetry("북마크 카운트 델타 소비", calculatedAt,
                () -> deltaProcessor.consumeAndApply().consumedEvents());
    }

    /**
     * 카운트 안전망 회차 — 매일 01:45 (KST). <b>표시 카운트 셋을 원본에서 다시 세고 아웃박스를
     * 같은 트랜잭션에서 비운다.</b>
     *
     * <p>매시 회차의 북마크 축이 델타가 되면서, 그 값의 정확성은 전표 발행·소비 규칙 위에 선다.
     * 규칙에 버그가 생기면 카운트가 조용히 어긋나는데 <b>오류도 로그도 없다</b> — 이 회차가
     * 그 표류의 상한을 하루로 자른다. 전량 재계산이 버려지지 않고 여기로 내려온 것이지, 새로 생긴
     * 문장이 아니다 ({@code docs/design/2026-08-17-bookmark-outbox-delta.md} 4-4).
     *
     * <p><b>01:45인 이유는 다른 배치들과 시각을 가르기 위해서다.</b> 01:00 점수 회차는
     * {@code place_stats} 전 행에 X 락을 커밋까지 들고, 01:30 매시 회차는 같은 아웃박스 전표를
     * {@code FOR UPDATE}로 잡는다. 03:00 장소 임베딩·04:00 코스 임베딩과도 떨어져 있어
     * {@code @Scheduled} 단일 스레드를 두고 다투지 않는다.
     *
     * <p>{@code lockAtMostFor}·{@code lockAtLeastFor}의 근거는 매시 회차와 같다. 재계산 문장이
     * 매시 회차에서 내려온 그 문장이므로 최장 시간의 셈도 그대로다.
     */
    @Scheduled(cron = "${solply.place-stats.count-safety-cron:0 45 1 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "place-stats-count-safety", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recalculatePlaceCountsSafety() {
        LocalDateTime calculatedAt = LocalDateTime.now();
        log.info("인기순 카운트 안전망 배치 시작 - calculatedAt={}", calculatedAt);

        runWithRetry("인기순 카운트 안전망 배치", calculatedAt,
                () -> batchProcessor.recalculateCountsAndClearOutbox(calculatedAt));
    }

    /**
     * 회차를 <b>재시도까지 포함해</b> 돌린다. 한 번이라도 성공하면 {@code true}.
     *
     * <p><b>여기가 트랜잭션 밖이라는 것이 이 메서드의 전제다.</b> 프로세서가 시도마다 자기
     * 트랜잭션을 열고 닫으므로, 실패한 시도는 온전히 롤백된 뒤 다음 시도가 깨끗한 상태에서
     * 시작한다. 이 루프를 트랜잭션 안으로 옮기면 실패한 문장 뒤에 계속 쓰는 모양이 된다.
     *
     * <p><b>{@code calculatedAt}을 인자로 받아 모든 시도에 같은 값을 넘기는 것이 계약이다.</b>
     * 집계에 {@code created_at <= :calculatedAt} 상한이 있어 기준 시각이 같으면 결과가 같다 —
     * 재시도가 안전한 근거가 그 멱등성 하나다. 시도마다 {@code LocalDateTime.now()}를 새로
     * 잡으면 그 사이 들어온 북마크·리뷰가 결과를 바꿔 "다시 돌려도 같다"가 깨진다.
     *
     * <p><b>델타 소비만 예외다 — 거기서 {@code calculatedAt}은 로그 표식일 뿐이다.</b> 그쪽의
     * 재시도 안전성은 기준 시각이 아니라 적용과 삭제가 한 트랜잭션이라는 성질에서 나온다
     * ({@code BookmarkCountDeltaProcessor#consumeAndApply}).
     *
     * <p>마지막 시도까지 실패하면 {@code error}, 중간 실패는 {@code warn}으로 남긴다. 둘을 가르지
     * 않으면 "재시도로 복구된 회차"와 "끝내 죽은 회차"가 알림에서 같은 무게로 울린다.
     *
     * <p>예외를 스케줄러 스레드로 흘리지 않는 것은 예전과 같다. 여기서 던지면
     * {@code @Scheduled} 기본 실행기가 단일 스레드라 이후 회차의 등록에까지 영향을 준다.
     */
    private boolean runWithRetry(String label, LocalDateTime calculatedAt, IntSupplier attempt) {
        int maxAttempts = placeStatsProperties.getBatchMaxAttempts();
        for (int n = 1; n <= maxAttempts; n++) {
            long startNanos = System.nanoTime();
            try {
                int affected = attempt.getAsInt();
                log.info("{} 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms, 시도={}/{}",
                        label, calculatedAt, affected,
                        Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), n, maxAttempts);
                return true;
            } catch (Exception e) {
                if (n == maxAttempts) {
                    log.error("{} 실패 - {}회 시도 모두 실패해 회차를 포기한다, calculatedAt={}",
                            label, maxAttempts, calculatedAt, e);
                    return false;
                }
                Duration delay = placeStatsProperties.getBatchRetryDelay();
                log.warn("{} 실패 - {}ms 뒤 재시도한다 (시도 {}/{}), calculatedAt={}",
                        label, delay.toMillis(), n, maxAttempts, calculatedAt, e);
                if (!sleepBeforeRetry(label, delay)) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 시도 사이 대기. 중단되면 {@code false}를 돌려 회차를 그 자리에서 접는다.
     *
     * <p>인터럽트는 대개 종료 신호다. 삼키고 재시도를 이어가면 셧다운이 그만큼 늦어지고,
     * 플래그를 복원하지 않으면 상위가 종료 중임을 영영 알 수 없다.
     */
    private boolean sleepBeforeRetry(String label, Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 재시도 대기가 중단됐다 - 회차를 접는다", label);
            return false;
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
        log.info("인기점수 배치 시작 - calculatedAt={}", calculatedAt);
        // 재시도가 카운트보다 여기서 더 값어치 있다 — 회차 간격이 24시간이라 한 번 죽으면
        // 하루치 점수가 낡는다. 점수는 정렬 축이라 그 낡음이 표시값이 아니라 순서로 드러난다.
        runWithRetry(
                "인기점수 배치", calculatedAt, () -> batchProcessor.recalculateScores(calculatedAt));
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
     * 돌아 {@link org.sopt.solply_server.domain.place.repository.PlaceStatsRepository#rebuildRowsFromSource}
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
