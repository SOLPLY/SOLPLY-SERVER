package org.sopt.solply_server.domain.place.service.facade;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.sopt.solply_server.domain.place.service.job.PlaceStatsJobKind;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code place_stats} 집계 배치의 진입점. 정기 스케줄 <b>넷</b>과 부팅 시 최초 적재를 연다.
 *
 * <p><b>회차는 타이머의 발화 그 자체다.</b> 발화를 DB에 남기지 않으므로, 발화 순간에 인스턴스가
 * 죽거나 락을 쥔 쪽이 사라지면 그 회차는 그대로 없던 일이 된다. <b>메우는 것은 다음 정시 발화
 * 하나뿐이고, 지나간 발화를 되짚지 않는다</b> — 네 회차가 모두 그 시점의 원본에서 값을 다시 만드는
 * 전량 재계산(델타 소비는 그때까지 쌓인 전표 전부)이라, 놓친 발화를 소급해 돌려도 다음 발화 한 번의
 * 결과와 같기 때문이다. 값이 낡은 채로 남는 상한이 곧 회차 간격이다.
 *
 * <p><b>이 배치는 목록 캐시를 모른다.</b> 여기서 하는 일은 {@code place_stats}를 고치는 것까지이고,
 * 그 값이 목록에 나타나는 것은 프로세서가 <b>자기 트랜잭션 안에서</b> 올린 스냅샷 번호를 각
 * 인스턴스의 폴이 보고 자기 원본을 다시 읽는 것이다.
 *
 * <p><b>주기는 값마다 신선도 요구가 다르기 때문에 갈려 있다.</b>
 * <ol>
 *   <li><b>북마크 카운트 델타 소비 — 매시 :00 :15 :30 :45 (KST).</b> 아웃박스 전표를 삼켜
 *       {@code bookmark_count}에 더한다(2026-08-17). 북마크 전량 스캔(1,040만 행, 옛 회차 비용의
 *       98%)이 빠지면서 이 회차의 비용은 총 행 수가 아니라 <b>지난 회차 이후의 토글 수</b>에 붙는다.</li>
 *   <li><b>리뷰 카운트 — 매시 :05 :20 :35 :50 (KST).</b> 화면에 찍히는 리뷰 수·평점이고, 방금 쓴
 *       리뷰가 <em>수</em>에 반영되는 지연이 곧 이 간격이다. 전량 재계산이다(2026-08-15 창 관측
 *       117ms — <b>그때 그 데이터 규모의 값이고 시간당 4회가 된 지금의 비용을 보장하지 않는다</b>).
 *       <b>이 회차가 만지는 것은 그 두 값뿐이고, 그중에서도 값이 실제로 달라진 행뿐이다</b> (V35) —
 *       파생 컬럼({@code town_id}·{@code tag_bitmask}·{@code created_at})과 행의 존재 여부는
 *       어드민 쓰기 트랜잭션의 소유라 여기서 손대지 않는다 ({@code AdminPlaceService}).</li>
 *   <li><b>인기 점수 — 매시 :10 (KST).</b> 반감기 90일에서 한 시간의 감쇠 변화는 순위를 흔들지
 *       못하므로 이 회차가 잦아진 이유는 감쇠가 아니라 <b>카운트가 바뀐 뒤 점수가 따라붙는 지연</b>이다.
 *       점수가 갈리는 순간이 곧 커서 좌표계가 갈리는 순간이라, 그 창을 새벽 수 초로 몰아 두던
 *       편익은 이제 없다 — 대신 아래 신선도 문단의 대가를 진다.</li>
 *   <li><b>카운트 안전망 — 매일 01:25 (KST).</b> <b>혼자 하루 1회로 남은 회차다.</b>
 *       <b>북마크 수만</b> 원본에서 다시 세고 아웃박스를 같은 트랜잭션에서 비운다 — 델타 위에 선
 *       축이 그것 하나이기 때문이다. 15분마다 돌리면 델타 설계가 걷어낸 북마크 전량 스캔이 그대로
 *       되돌아오므로, 빈도를 올리는 것이 이 회차의 목적과 정면으로 어긋난다.
 *       근거는 {@link #recalculatePlaceCountsSafety()}.</li>
 * </ol>
 *
 * <p><b>네 회차는 같은 분에 겹치지 않는다.</b> 점수 문장은 {@code place_stats} 전 행을 갱신하고,
 * 안전망과 델타 소비는 아웃박스 전표라는 공통 관문을 지난다. 그래서 :00(델타) · :05(리뷰) ·
 * :10(점수) · :15(델타) · :20(리뷰) · 01:25(안전망) · :30(델타) … 로 <b>최소 5분</b>씩 벌려 뒀다.
 * <b>5분이 충분하다는 보장은 없다</b> — 회차 소요는 데이터 양과 그때의 DB 상태에 달렸다.
 * 특히 <b>전표를 두고 다투는 안전망(01:25)과 델타(01:30)의 거리가 5분이다.</b> 안전망이 5분을
 * 넘기면 :30 델타는 전표 잠금 뒤에서 기다린다. {@code lockAtMostFor PT10M}은 이 5분보다 길다.
 * 간격이 실제로 지켜지는지는 회차 종료 로그의 {@code elapsed}로 본다 —
 * <b>주기나 재시도 설정을 바꿀 때 이 간격을 함께 확인할 것.</b>
 * 락 이름을 넷으로 나눠 둔 것은 그 반대 이유다 — 하나로 묶으면 같은 시각에 겹친 회차 중 하나가
 * 통째로 건너뛰어진다.
 *
 * <p><b>신선도의 대가.</b> 집계가 값을 고칠 때마다 스냅샷 번호 둘이 오르는 계약은 그대로이므로,
 * 델타가 전표를 삼킬 때마다 커서 회차가 올라간다 — 즉 <b>스크롤 만료가 15분마다 생길 수 있다.</b>
 * 계약을 바꾸지 않고 빈도만 올린 결과다.
 *
 * <p><b>네 회차는 배치 풀(스레드 4)에서 돌고, 목록 스냅샷 폴은 전용 스레드에 있다</b>
 * ({@code SchedulingConfig}). 그래서 회차 하나가 길어져도 다른 회차나 폴의 발화를 밀지 않는다.
 * <b>같은 회차가 자기 자신과 겹치는 일도 없다</b> — 스프링은 메서드가 끝난 뒤에 다음 cron 시각을
 * 계산하므로, 회차가 자기 다음 발화를 지나치면 <b>그 발화는 건너뛰어진다.</b> 그렇게 건너뛴 발화는
 * 아무 데도 남지 않는다 — 되짚는 장치가 없으므로 다음 발화까지 값이 낡는다.
 *
 * <p><b>리더 선출(ShedLock)이 지키는 것과 지키지 못하는 것.</b> 회차당 락 하나로 "정시에 한
 * 인스턴스만 돈다"를 만들어 중복 비용을 자른다. 그러나 {@code lockAtMostFor}는 <b>임대</b>다 —
 * 회차가 그 상한을 넘겨 돌면 락이 풀린 채 계속 돌고, 그 창에서 다른 인스턴스가 같은 회차를
 * 시작할 수 있다. <b>그 겹침을 막는 장치는 없다.</b> 회차가 얼마나 걸릴지에 상한이 없으므로 창을
 * 없앨 수는 없고, 상한을 관측된 소요보다 넉넉히 잡아 확률을 낮출 뿐이다.
 * <b>겹쳤을 때 값이 어떻게 되는지는 확인하지 않았다</b> — 두 인스턴스는 각자
 * {@code LocalDateTime.now()}를 쓰므로 {@code calculatedAt}과 읽기 뷰가 다르고, 나중에 커밋한
 * 쪽이 이긴다. 같은 기준 시각에 대한 재실행이 같은 결과를 낸다는 성질(아래 재시도의 근거)은
 * <b>서로 다른 기준 시각으로 겹쳐 도는 경우를 아무것도 보장하지 않는다.</b>
 * ("멱등이니 병렬로 쪼개도 된다"로 확장하지 말 것 — 쪼개면 부분 반영 상태가 생긴다.)
 *
 * <p><b>이 클래스에 {@code @Transactional}을 붙이지 말 것 — Processor를 분리한 핵심 이유다.</b>
 * {@code try/catch}가 트랜잭션 경계 <b>바깥</b>에 있어야 한다. 한 메서드로 합치면 예외를 잡는
 * 지점이 트랜잭션 안이 되어, 실패한 문장 뒤에 커밋을 시도하는 모양이 된다. 붙는 순간 프로세서가
 * 이 트랜잭션에 <b>참여</b>해 그쪽의 {@code isolation} 지정이 조용히 버려지는 것도 같은 무게의
 * 이유다 ({@link PlaceStatsBatchProcessor} javadoc).
 * (참고: {@code @Scheduled} 메서드에 {@code @Transactional}을 직접 붙여도 RC 자체는 정상
 * 적용된다 — {@code ScheduledAnnotationBeanPostProcessor}가 {@code LOWEST_PRECEDENCE}라
 * auto-proxy creator보다 나중에 돌아 프록시를 등록한다. 즉 "합치면 RC가 무시된다"는 이유가
 * 아니다. 합치면 안 되는 이유는 위의 예외 처리 위치와 참여 문제다.)
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
    /** 델타 소비 회차. 트랜잭션 경계를 스스로 가지므로 여기서는 부르기만 한다 */
    private final BookmarkCountDeltaProcessor deltaProcessor;
    private final PlaceStatsProperties placeStatsProperties;

    /**
     * 북마크 카운트 델타 소비 회차 — 매시 :00 :15 :30 :45 (KST). <b>아웃박스 전표를 삼켜
     * {@code bookmark_count}에 더한다.</b>
     *
     * <p><b>리뷰 축과 락 이름을 공유하면 안 된다.</b> 겹친 시각에 깨어났을 때 한쪽이 통째로
     * 건너뛰어진다. {@code shedlock} 행은 첫 발화 때 ShedLock이 만들므로 락 이름에 마이그레이션이
     * 필요 없다 ({@code V27}). cron 키는 {@code bookmark-delta-cron}이다.
     *
     * <p><b>재시도가 안전한 근거가 다른 셋과 다르다.</b> 여기서는 기준 시각의 멱등성이 아니라
     * <b>claim·합산·적용·삭제가 한 트랜잭션이라는 원자성</b>이다 — 실패한 시도는 표식까지 함께
     * 롤백돼 전표를 처음 상태로 남긴다 ({@code BookmarkCountDeltaProcessor#consumeAndApply}).
     * 그래서 {@code calculatedAt}이 이 회차의 계산에는 쓰이지 않고 로그 표식일 뿐이다.
     *
     * <p>{@code lockAtMostFor}·{@code lockAtLeastFor}의 근거는 리뷰 축과 같다. 이 회차의 비용은
     * 총 행 수가 아니라 지난 회차 이후 쌓인 전표 수에 붙는다.
     */
    @Scheduled(cron = "${solply.place-stats.bookmark-delta-cron:0 0/15 * * * *}", zone = "Asia/Seoul")
    @SchedulerLock(
            name = "place-stats-bookmark-delta", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void consumeBookmarkCountDeltas() {
        // 델타 소비의 affectedRows는 리뷰 축과 성질이 정반대다 — 장소 수가 아니라 이번 회차가
        // 삼킨 전표 수, 곧 지난 회차 이후의 토글 수다. 이 수치가 회차의 비용 그 자체이므로 로그로
        // 남는 값도 그쪽이어야 한다.
        runRound(PlaceStatsJobKind.BOOKMARK_DELTA,
                calculatedAt -> deltaProcessor.consumeAndApply().consumedEvents());
    }

    /**
     * 리뷰 카운트 회차 — 매시 :05 :20 :35 :50 (KST).
     * <b>{@code review_count}·{@code avg_rating} 전량 재계산.</b>
     *
     * <p><b>cron 키 {@code count-cron}과 락 이름 {@code place-stats-count}는 옛 통합 회차의
     * 것을 그대로 쓴다.</b> "배치가 마지막으로 돈 시각"을 {@code shedlock} 한 행의
     * {@code locked_at}으로 보던 관찰이 끊기지 않는다
     * ({@code V35__drop_place_stats_count_calculated_at.sql}).
     *
     * <p><b>15분 오프셋을 둔 이유</b>는 같은 분에 델타 소비(:00 :15 :30 :45)와 겹치지 않게 하는
     * 것이다. 정각을 비워 두는 것은 장소 임베딩(03:00)·코스 임베딩(04:00)과의 스케줄러 스레드
     * 경합 회피이기도 하다.
     *
     * <p><b>매시 배치에 {@code zone}이 필요한가 — 발화 시각만 보면 아니다.</b> 시간대가 무엇이든
     * 매시 :05는 매시 :05다(15분 단위 오프셋을 쓰는 지역이 아닌 한). 그럼에도 명시하는 이유는
     * <b>회차들의 시간대 계약을 한 줄로 읽히게 하기 위해서다</b> — 한쪽만 {@code zone}을 달아 두면
     * 다음 사람이 "카운트는 서버 시간대, 안전망은 KST"라는 있지도 않은 비대칭을 읽는다. 실제 계약은
     * "네 회차 모두 KST 벽시계"이고, 회차 사이의 간격이 그 위에서만 성립한다.
     *
     * <p>{@code lockAtMostFor PT10M} — 락 보유 인스턴스가 죽었을 때의 자동 해제 상한.
     * 10분을 고른 근거는 2026-08-15 창의 카운트 회차 관측(3.5초,
     * `docs/perf/2026-08-15-count-batch-duration-lock.md`)에 재시도 대기를 얹어 잡은 여유였다.
     * <b>그때의 관측이지 상한의 계산이 아니다</b> — 데이터가 늘거나 DB가 느린 순간에 걸리면 회차는
     * 얼마든지 길어질 수 있고, 그 창의 겹침은 아무것도 막지 못한다(클래스 javadoc).
     * 다음 회차 간격(15분)보다는 짧아야 페일오버가 성립한다 — 길게 잡으면 죽은 인스턴스의 락이
     * 다음 회차까지 살아 배치가 통째로 건너뛰어진다.
     * <b>{@code review-count-max-attempts}를 올릴 때 이 상한을 함께 볼 것.</b>
     *
     * <p>{@code lockAtLeastFor PT1M} — 배치가 수 초 만에 끝나도 1분간은 락을 유지한다.
     * 인스턴스 간 발화 시각이 수 초 어긋나도 뒤늦게 깨어난 쪽이 "이미 풀린 락"을 잡아
     * 같은 회차를 다시 돌리지 않게 하는 장치다.
     */
    @Scheduled(cron = "${solply.place-stats.count-cron:0 5/15 * * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "place-stats-count", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recalculateReviewCounts() {
        // affectedRows는 문장이 걸린 행 수(matched)이고, 순수 UPDATE인 지금은 그것이 곧
        // place_stats 행 수 = 목록 노출 대상 장소 수다. 배치가 실제로 쓴 행 수가 아니다 —
        // 회차 시각 컬럼을 걷어낸 뒤로(V35) 실제 쓰기는 카운트가 달라진 장소로 좁혀졌는데,
        // 이 수치는 그와 무관하게 전 행을 센다. 둘이 갈라진 것이 V35의 실익 그 자체다.
        runRound(PlaceStatsJobKind.REVIEW_COUNT, batchProcessor::recalculateReviewCounts);
    }

    /**
     * 인기 점수 회차 — 매시 :10 (KST).
     *
     * <p><b>{@code zone}을 명시하는 이유.</b> {@code TimezoneConfig}가 JVM 기본 시간대를 이미
     * Asia/Seoul로 고정하므로 생략해도 지금은 같은 시각에 돈다. 그럼에도 적어 두는 것은 네 회차의
     * 간격이 <b>하나의 벽시계 위에서만</b> 성립하기 때문이다.
     *
     * <p>{@code lockAtMostFor PT30M} — 회차 간격(1시간)의 절반이다. 점수 문장은
     * {@code place_stats} 전 행을 갱신하므로 데이터가 늘면 카운트 축보다 먼저 길어진다.
     * <b>간격이 24시간이던 시절의 "짧게 유지할 이유가 없다"는 근거는 더 이상 쓰지 않는다</b> —
     * 이제는 다음 발화를 막지 않는 상한이라는 것이 이 값의 근거다.
     *
     * <p><b>{@code calculatedAt}은 실행 시점이다.</b> 감쇠가 "돌았어야 할 시각"이 아니라 "실제로
     * 잰 시각" 기준으로 계산되므로 점수와 {@code score_calculated_at}이 서로 어긋나지 않는다.
     */
    @Scheduled(cron = "${solply.place-stats.score-cron:0 10 * * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "place-stats-score", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void recalculatePopularScores() {
        runRound(PlaceStatsJobKind.POPULAR_SCORE, batchProcessor::recalculateScores);
    }

    /**
     * 카운트 안전망 회차 — 매일 01:25 (KST). <b>북마크 수를 원본에서 다시 세고 아웃박스를
     * 같은 트랜잭션에서 비운다.</b>
     *
     * <p>북마크 축이 델타가 되면서, 그 값의 정확성은 전표 발행·소비 규칙 위에 선다.
     * 규칙에 버그가 생기면 카운트가 조용히 어긋나는데 <b>오류도 로그도 없다</b> — 이 회차가
     * 그 표류의 상한을 하루로 자른다 ({@code docs/design/2026-08-17-bookmark-outbox-delta.md} 4-4).
     * <b>이 회차만 하루 1회로 남은 이유가 그것이다</b> — 15분마다 돌리면 델타 설계가 걷어낸 북마크
     * 전량 스캔(1,040만 행)이 그대로 되돌아온다.
     *
     * <p><b>리뷰 값은 건드리지 않는다.</b> 리뷰 수·평균 평점의 주인은 리뷰 회차이고, 그쪽이 15분마다
     * 전량 재계산하므로 여기서 함께 세면 같은 값을 하루에 한 번 더 세는 중복이다.
     * <b>이 회차가 지키는 축은 북마크 하나다</b> — 델타 위에 선 축이 그것뿐이기 때문이다
     * ({@code PlaceStatsRepository#updateBookmarkCountsFromSource}).
     *
     * <p><b>집계 기준 시각은 언제나 실행 시점이다.</b> 이 회차는 <b>자기가 표시한 아웃박스 전표를
     * 같은 트랜잭션에서 지우므로</b>, 과거 시각을 기준으로 돌면 그 사이 들어온 토글이 재계산에는
     * 빠진 채 전표만 지워져 카운트가 유실된다. 놓친 발화를 소급하지 않는 이유 중 하나가 이것이다.
     *
     * <p><b>01:25인 이유는 다른 배치들과 시각을 가르기 위해서다.</b> :20 리뷰 회차와 :30 델타 회차
     * 사이에 있고, 03:00 장소 임베딩·04:00 코스 임베딩과도 떨어져 있다. <b>전표를 두고 다투는 델타
     * 회차와의 거리가 5분뿐</b>이라는 한계는 클래스 javadoc에 적었다.
     */
    @Scheduled(cron = "${solply.place-stats.count-safety-cron:0 25 1 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "place-stats-count-safety", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recalculatePlaceCountsSafety() {
        runRound(PlaceStatsJobKind.COUNT_SAFETY, batchProcessor::recalculateCountsAndClearOutbox);
    }

    /**
     * 회차 하나 = 시작 줄 · 시도들 · 종료 줄.
     *
     * <p><b>{@code calculatedAt}을 여기서 한 번만 잡아 모든 시도에 넘기는 것이 계약이다.</b>
     * 집계에 {@code created_at <= :calculatedAt} 상한이 있어 기준 시각이 같으면 결과가 같다 —
     * 재시도가 안전한 근거가 그 멱등성 하나다. 시도마다 새로 잡으면 그 사이 들어온 북마크·리뷰가
     * 결과를 바꿔 "다시 돌려도 같다"가 깨진다. <b>델타 소비만 예외로, 거기서는 원자성이 근거이고
     * 이 값은 로그 표식일 뿐이다</b> ({@link #consumeBookmarkCountDeltas()}).
     *
     * <p><b>이 값은 JVM 기본 시간대로 읽는다.</b> 비교 대상이 엔티티가 써 넣은
     * {@code created_at}이라 발화 시각의 벽시계가 아니라 애플리케이션의 시계를 따라야 한다.
     *
     * <p>시작 로그가 없으면 "배치가 도는 중"과 "스케줄이 애초에 안 돌은 상태"를 로그로 구분할 수
     * 없다. 락을 못 잡은 인스턴스는 블록 없이 회차를 건너뛰므로 이 줄도 남기지 않는다 — 즉 회차마다
     * 이 줄은 클러스터 전체에서 정확히 한 번 찍힌다(락이 살아 있는 동안은).
     */
    private void runRound(PlaceStatsJobKind kind, ToIntFunction<LocalDateTime> round) {
        LocalDateTime calculatedAt = LocalDateTime.now();
        log.info("{} 배치 시작 - calculatedAt={}", kind.getLabel(), calculatedAt);
        long roundStartNanos = System.nanoTime();
        boolean succeeded =
                runWithRetry(kind, calculatedAt, () -> round.applyAsInt(calculatedAt));
        logRoundFinished(kind, calculatedAt, roundStartNanos, succeeded);
    }

    /**
     * 회차의 종료 줄. <b>{@code runWithRetry}의 시도별 완료 로그와 재는 구간이 다르다</b> —
     * 저쪽은 성공한 시도 하나의 소요이고, 여기는 실패한 시도와 재시도 대기까지 포함한 회차 전체다.
     * 이 값이 {@code lockAtMostFor}·회차 간격과 비교할 수 있는 유일한 수치다.
     *
     * <p>회차마다 시작 줄 하나와 이 종료 줄 하나가 짝을 이룬다. {@code runWithRetry}는 예외를
     * 삼키고 인터럽트에도 {@code false}를 돌려주므로 실패한 회차·중단된 회차도 이 줄을 남긴다 —
     * 짝이 없는 시작 줄은 {@code Error}처럼 스케줄러 스레드를 통째로 빠져나간 경우뿐이다.
     *
     * <p><b>실패해도 {@code info}로 남기는 것은 의도다.</b> 실패의 원인과 스택은
     * {@code runWithRetry}가 이미 {@code error}로 냈다. 여기서 한 번 더 올리면 회차 하나가 알림을
     * 두 번 울린다 — 이 줄이 더하는 것은 경보가 아니라 소요 시간이다.
     */
    private void logRoundFinished(PlaceStatsJobKind kind, LocalDateTime calculatedAt,
            long roundStartNanos, boolean succeeded) {
        log.info("{} 회차 종료 - 결과={}, calculatedAt={}, elapsed={}ms",
                kind.getLabel(), succeeded ? "성공" : "실패", calculatedAt,
                Duration.ofNanos(System.nanoTime() - roundStartNanos).toMillis());
    }

    /**
     * 회차를 <b>재시도까지 포함해</b> 돌린다.
     *
     * <p><b>여기가 트랜잭션 밖이라는 것이 이 메서드의 전제다.</b> 프로세서가 시도마다 자기
     * 트랜잭션을 열고 닫으므로, 실패한 시도는 온전히 롤백된 뒤 다음 시도가 깨끗한 상태에서
     * 시작한다. 이 루프를 트랜잭션 안으로 옮기면 실패한 문장 뒤에 계속 쓰는 모양이 된다.
     *
     * <p>마지막 시도까지 실패하면 {@code error}, 중간 실패는 {@code warn}으로 남긴다. 둘을 가르지
     * 않으면 "재시도로 복구된 회차"와 "끝내 죽은 회차"가 알림에서 같은 무게로 울린다.
     *
     * <p><b>포기한 회차는 다음 정시 발화까지 그대로 낡는다.</b> 되짚는 장치가 없으므로 그 사이를
     * 메우는 것은 없다 — 값이 낡는 상한이 곧 회차 간격이다.
     *
     * <p><b>예외를 여기서 잡는 이유는 재시도와 로그 등급이다</b> — 스케줄러 스레드를 지키려는 것이
     * 아니다. 스프링은 반복 작업을 {@code DelegatingErrorHandlingRunnable}로 감싸 예외를 로그로
     * 삼키고({@code LOG_AND_SUPPRESS}) 다음 발화를 이어 가므로, 흘려보내도 스케줄이 죽지는 않는다.
     * 대신 재시도가 사라지고 모든 실패가 같은 등급으로 찍힌다.
     *
     * <p><b>시도 횟수·대기를 {@link PlaceStatsJobKind}에서 읽는 것이 계약이다</b> — 회차마다 갈린
     * 설정의 대응을 그 표 한 곳에만 둔다. 파사드 메서드 넷에 흩어져 있으면 새 키를 더하거나 옮길 때
     * 빠뜨린 회차가 눈에 띄지 않는다.
     *
     * @return 어느 시도든 성공했으면 {@code true}
     */
    private boolean runWithRetry(
            PlaceStatsJobKind kind, LocalDateTime calculatedAt, IntSupplier attempt) {
        String label = kind.getLabel();
        int maxAttempts = kind.maxAttempts(placeStatsProperties);
        Duration retryDelay = kind.retryDelay(placeStatsProperties);
        for (int n = 1; n <= maxAttempts; n++) {
            long startNanos = System.nanoTime();
            try {
                int affectedRows = attempt.getAsInt();
                log.info("{} 완료 - calculatedAt={}, affectedRows={}, elapsed={}ms, 시도={}/{}",
                        label, calculatedAt, affectedRows,
                        Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), n, maxAttempts);
                return true;
            } catch (Exception e) {
                if (n == maxAttempts) {
                    log.error("{} 실패 - {}회 시도 모두 실패해 이번 회차를 포기한다"
                                    + "(다음 정시 발화까지 값이 낡는다), calculatedAt={}",
                            label, maxAttempts, calculatedAt, e);
                    return false;
                }
                log.warn("{} 실패 - {}ms 뒤 재시도한다 (시도 {}/{}), calculatedAt={}",
                        label, retryDelay.toMillis(), n, maxAttempts, calculatedAt, e);
                if (!sleepBeforeRetry(label, retryDelay)) {
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
     * "채워져 있다"라서, 점수 쪽이 같은 가드를 쓰면 다음 발화까지 전 장소가 0점이 된다.
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
