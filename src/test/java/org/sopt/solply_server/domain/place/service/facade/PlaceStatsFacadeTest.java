package org.sopt.solply_server.domain.place.service.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor;
import org.sopt.solply_server.domain.place.service.BookmarkCountDeltaProcessor.DeltaResult;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PlaceStatsFacadeTest {

    /**
     * {@code @Scheduled}가 걸린 회차 전부. <b>순서는 발화 시각 순이다</b>(매시 :30 · 매시 :15 ·
     * 01:45 · 01:00이 아니라 선언 순서 — 아래 단언들이 목록을 그대로 돌므로 누락이 곧 빠진 회차다).
     * 회차를 늘리면 여기에 더하는 것이 시간대·락 이름·발화 충돌 검사에 자동으로 들어간다.
     */
    private static final List<String> SCHEDULED_METHODS = List.of(
            "recalculateReviewCounts",
            "consumeBookmarkCountDeltas",
            "recalculatePlaceCountsSafety",
            "recalculatePopularScores");

    @Mock
    private PlaceStatsBatchProcessor batchProcessor;

    /**
     * 매시 회차의 북마크 축. 리뷰 축과 <b>다른 빈</b>이라는 것 자체가 계약이라 목도 따로 둔다 —
     * 델타 소비는 자기 트랜잭션을 가져야 하고, 그래서 파사드가 부르는 대상이 둘이다.
     */
    @Mock
    private BookmarkCountDeltaProcessor deltaProcessor;

    /**
     * 실물을 쓰는 이유는 위와 같다 — 재시도 횟수의 <b>기본값</b>이 그대로 도는 것을 보려는 것이다.
     * mock이면 {@code getBatchMaxAttempts()}가 0을 돌려줘 회차가 한 번도 안 돈다.
     */
    private final PlaceStatsProperties placeStatsProperties = new PlaceStatsProperties();

    private PlaceStatsFacade placeStatsFacade;

    @BeforeEach
    void createFacade() {
        // 시도 횟수는 기본값(3)을 그대로 쓰고 대기만 지운다. 대기를 남기면 실패 경로를 거치는
        // 테스트마다 (시도 횟수 - 1) × 5초를 잠든다.
        // 2026-09-12부터 매시 두 축이 자기 대기 키를 갖는다 — 공통 키만 0으로 두면 그 둘은
        // 여전히 5초씩 잔다. 회차마다 따로 꺼야 한다는 사실 자체가 분리의 관측 가능한 형태다.
        placeStatsProperties.setBatchRetryDelay(Duration.ZERO);
        placeStatsProperties.setReviewCountRetryDelay(Duration.ZERO);
        placeStatsProperties.setBookmarkDeltaRetryDelay(Duration.ZERO);
        // 델타 소비의 기본 응답은 "빈 아웃박스"다. 목의 기본값(null)을 그대로 두면 파사드가
        // 결과에서 전표 수를 꺼내다 NPE로 죽어, 회차마다 실패 로그가 하나씩 덤으로 붙는다.
        // lenient인 것은 점수 회차만 보는 테스트들이 이 스텁을 쓰지 않기 때문이다.
        lenient().when(deltaProcessor.consumeAndApply()).thenReturn(new DeltaResult(0, 0));
        placeStatsFacade = new PlaceStatsFacade(
                batchProcessor, deltaProcessor, placeStatsProperties);
    }

    /**
     * 예외를 삼키는 배치라 <b>로그가 유일한 신호</b>다. 레벨이 error 아래로 내려가거나 로그가 통째로
     * 사라지면 배치가 매일 실패해도 아무도 모른다 — 그 회귀를 여기서 잡으려고 appender를 붙인다.
     */
    private ListAppender<ILoggingEvent> logAppender;
    private Logger facadeLogger;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        facadeLogger = (Logger) LoggerFactory.getLogger(PlaceStatsFacade.class);
        facadeLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        facadeLogger.detachAppender(logAppender);
    }

    // === 매시 두 회차 ===

    @Test
    @DisplayName("리뷰 카운트 회차는 하나의 기준 시각으로 리뷰 축 프로세서를 1회 호출한다")
    void runsReviewCountBatchOnceWithSingleTimestamp() {
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculateReviewCounts();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchProcessor).recalculateReviewCounts(captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    /**
     * <b>2026-09-12 분리로 이 계약이 뒤집혔다.</b> 예전에는 한 진입점이 두 축을 차례로 돌리는 것이
     * 계약이었고, 그때의 단언은 "둘 다 불렀는가"였다. 지금은 회차가 갈렸으므로
     * <b>각 진입점이 자기 축만 부르는가</b>가 그 자리를 대신한다 — 한쪽이 다른 쪽까지 부르면
     * 락 이름을 나눠 둔 의미가 사라지고 :15와 :30에 같은 일이 두 번 돈다.
     */
    @Test
    void 두_매시_회차는_서로의_축을_부르지_않는다() {
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculateReviewCounts();

        verify(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        verify(deltaProcessor, never()).consumeAndApply();

        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(deltaProcessor).consumeAndApply();
        // 리뷰 축 호출은 여전히 첫 회차의 1회뿐이다
        verify(batchProcessor, times(1)).recalculateReviewCounts(any(LocalDateTime.class));
    }

    /**
     * <b>매시 회차는 표시 카운트 셋 전량 재계산을 부르지 않는다.</b> 그 문장은 새벽 안전망으로
     * 내려갔고, 매시에 되돌아오면 이 전환이 사려던 것(북마크 전량 스캔 제거)이 통째로 사라진다.
     * 게다가 그 문장은 아웃박스를 비우는 짝과 함께여야 해서, 여기로 돌아오면 이중 반영도 따라온다.
     */
    @Test
    void 매시_두_회차_어느_쪽도_전량_재계산을_부르지_않는다() {
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(batchProcessor, never()).recalculateCounts(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateCountsAndClearOutbox(any(LocalDateTime.class));
    }

    /**
     * <b>두 회차는 서로의 진입점을 부르지 않는다.</b> 한쪽이 다른 쪽까지 돌리면 주기를 가른 의미가
     * 사라지고(카운트 배치가 매시 점수를 다시 계산한다), 무엇보다 두 배치가 같은 시각에 겹쳐
     * {@code place_stats} 전 행 X 락을 두고 서로를 기다린다.
     */
    @Test
    void 매시_두_회차는_점수를_건드리지_않는다() {
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(batchProcessor, never()).recalculateScores(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("리뷰 축 프로세서가 실패해도 스케줄러 스레드로 예외를 던지지 않는다")
    void swallowsReviewCountProcessorFailure() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculateReviewCounts();

        verify(batchProcessor, times(placeStatsProperties.getReviewCountMaxAttempts()))
                .recalculateReviewCounts(any(LocalDateTime.class));
    }

    /**
     * <b>한 축이 죽어도 다른 축은 돈다.</b> 근거가 2026-09-12에 바뀌었다 — 예전에는 "같은 메서드
     * 안에서 순차로 부르되 앞의 실패를 삼킨다"였고, 지금은 <b>애초에 다른 회차·다른 락·다른 시각</b>이다.
     * 분리가 이 보장을 약화시키지 않고 오히려 구조로 굳혔다는 것을 여기서 고정한다 —
     * 리뷰 축이 최대 시도까지 모두 실패한 뒤에도 델타 소비는 자기 회차에서 온전히 돈다.
     */
    @Test
    void 리뷰_축이_모두_실패해도_델타_소비_회차는_온전히_돈다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(deltaProcessor, times(1)).consumeAndApply();
    }

    /**
     * 델타 소비도 재시도를 받는다. 근거는 리뷰 축의 멱등성이 아니라 소비의 트랜잭션성이다 —
     * 적용과 삭제가 한 트랜잭션이라 실패한 시도는 전표를 그대로 남기고 롤백된다.
     */
    @Test
    void 델타_소비가_실패하면_자기_키의_최대_시도까지_재시도한다() {
        willThrow(new RuntimeException("boom")).given(deltaProcessor).consumeAndApply();

        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(deltaProcessor, times(placeStatsProperties.getBookmarkDeltaMaxAttempts()))
                .consumeAndApply();
    }

    /**
     * <b>두 축이 서로 다른 재시도 키를 따른다.</b> 이것이 분리가 실제로 산 것 중 하나다 —
     * 한쪽의 시도 횟수를 올려도 다른 쪽의 회차 길이가 따라 늘지 않는다.
     * 공통 키({@code batch-*})로 되돌아가는 회귀는 기능 단언을 전부 통과하므로 여기서만 잡힌다.
     */
    @Test
    void 두_축은_서로_다른_재시도_설정을_따른다() {
        placeStatsProperties.setReviewCountMaxAttempts(1);
        placeStatsProperties.setBookmarkDeltaMaxAttempts(2);
        // 공통 키는 일부러 둘 중 어느 값과도 다르게 둔다 — 어느 쪽이든 이 값을 읽으면 드러난다
        placeStatsProperties.setBatchMaxAttempts(5);
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        willThrow(new RuntimeException("boom")).given(deltaProcessor).consumeAndApply();

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(batchProcessor, times(1)).recalculateReviewCounts(any(LocalDateTime.class));
        verify(deltaProcessor, times(2)).consumeAndApply();
    }

    // === 회차 내 재시도 ===

    /**
     * <b>재시도가 안전한 근거는 멱등성 하나이고, 그 멱등성은 기준 시각을 고정할 때만 성립한다.</b>
     * 집계에 {@code created_at <= :calculatedAt} 상한이 있어 같은 시각으로 다시 돌리면 결과가 같다.
     * 시도마다 {@code now()}를 새로 잡으면 그 사이 들어온 북마크·리뷰가 결과를 바꿔, 재시도가
     * "같은 회차를 다시 돌리는 것"이 아니라 "다른 회차를 도는 것"이 된다.
     *
     * <p>이 테스트가 깨지면 재시도의 전제가 깨진 것이다 — 횟수보다 이쪽이 본질이다.
     */
    @Test
    void 재시도는_첫_시도와_같은_기준_시각을_쓴다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculateReviewCounts();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchProcessor, times(placeStatsProperties.getReviewCountMaxAttempts()))
                .recalculateReviewCounts(captor.capture());
        assertThat(captor.getAllValues()).containsOnly(captor.getAllValues().getFirst());
    }

    /**
     * 재시도로 복구되면 그 회차는 <b>성공</b>이다. 앞선 실패가 error로 남으면 알림이 울리고,
     * 사람이 멀쩡히 복구된 회차를 들여다보게 된다 — 중간 실패는 warn, 최종 실패만 error다.
     */
    @Test
    void 중간에_성공하면_더_돌지_않고_실패는_warn으로만_남는다() {
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class)))
                .willThrow(new RuntimeException("boom"))
                .willReturn(10);

        placeStatsFacade.recalculateReviewCounts();

        verify(batchProcessor, times(2)).recalculateReviewCounts(any(LocalDateTime.class));
        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .isEmpty();
        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("재시도"));
    }

    /**
     * 점수 회차에도 같은 장치가 걸려 있어야 한다 — 회차 간격이 24시간이라 한 번 죽으면 하루치
     * 점수가 낡고, 점수는 정렬 축이라 그 낡음이 표시값이 아니라 순서로 드러난다.
     */
    @Test
    void 점수_회차도_실패하면_재시도한다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateScores(any(LocalDateTime.class));

        placeStatsFacade.recalculatePopularScores();

        verify(batchProcessor, times(placeStatsProperties.getBatchMaxAttempts()))
                .recalculateScores(any(LocalDateTime.class));
    }

    /**
     * {@code review-count-max-attempts: 1}은 재시도를 끄는 유효한 설정이다. 루프가 그 값을 무시하고
     * 최소 한 번은 더 도는 형태로 쓰이면 끌 방법이 없어진다.
     */
    @Test
    void 최대_시도_1이면_재시도하지_않는다() {
        placeStatsProperties.setReviewCountMaxAttempts(1);
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculateReviewCounts();

        verify(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .isEmpty();
    }

    // === 점수 회차 ===

    @Test
    void 점수_배치는_카운트를_건드리지_않는다() {
        given(batchProcessor.recalculateScores(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculatePopularScores();

        verify(batchProcessor).recalculateScores(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateReviewCounts(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateCounts(any(LocalDateTime.class));
        verify(deltaProcessor, never()).consumeAndApply();
    }

    @Test
    @DisplayName("점수 프로세서가 실패해도 스케줄러 스레드로 예외를 던지지 않는다")
    void swallowsScoreProcessorFailure() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateScores(any(LocalDateTime.class));

        placeStatsFacade.recalculatePopularScores();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> {
                    // 스택트레이스가 빠지면 "실패했다"만 알고 왜인지는 영영 모른다
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
                });
    }

    // === cron ===

    /**
     * {@code application.yml}은 {@code .gitignore}에 걸려 커밋되지 않는다. 즉 yml이 없는 환경
     * (신규 클론·CI)에서는 {@code solply.place-stats.*} 키가 <b>존재하지 않는다</b>.
     * 플레이스홀더에 기본값이 없으면 그 환경은 스케줄러 초기화 단계에서 기동이 통째로 실패한다.
     *
     * <p>로컬 yml에 키가 있으면 {@code contextLoads}는 이 결함을 절대 못 잡는다 — 컨텍스트 테스트는
     * 기본값 경로를 밟지 않기 때문이다. 그래서 키가 전혀 없는 빈 Environment로 직접 해석해 본다.
     *
     * <p><b>이 테스트는 주기의 값 자체도 지킨다.</b> 기본값 리터럴이
     * {@code @Scheduled}와 {@code PlaceStatsProperties} 두 곳에 <b>구조적으로 중복</b>돼 있어
     * (프로퍼티 필드 기본값은 플레이스홀더 해석 시점에 보이지 않는다) 한쪽만 고치면 조용히 갈라진다.
     * 아래 단언들이 그 중복을 묶는다 — 주기를 바꾸려면 <b>세 곳</b>(두 리터럴 + 이 기대값)을
     * 함께 고쳐야 하고, 그러지 않으면 여기서 멈춘다.
     *
     * <p>다음 실행 시각을 <b>두 번</b> 보는 이유: 정시 하나만 보면 "매일 00:30"도 통과한다.
     * 연속 두 회가 1시간 간격임을 함께 봐야 매시라는 것이 고정된다.
     */
    @Test
    @DisplayName("리뷰 카운트 cron 플레이스홀더는 프로퍼티가 없어도 매시 30분으로 해석된다")
    void reviewCountCronPlaceholderFallsBackToHourlyHalfPast() throws Exception {
        String resolved = resolvedCron("recalculateReviewCounts");

        LocalDateTime first =
                CronExpression.parse(resolved).next(LocalDateTime.of(2026, 7, 30, 0, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 7, 30, 0, 30));
        assertThat(CronExpression.parse(resolved).next(first))
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 1, 30));

        assertThat(new PlaceStatsProperties().getCountCron()).isEqualTo(resolved);
    }

    /**
     * 북마크 델타 회차의 cron 키는 <b>신설</b>이라 어느 환경의 yml에도 없다 — 기본값이 없으면
     * 모든 환경이 스케줄러 초기화에서 죽는다. 값도 함께 지킨다: :15는 점수(01:00)·리뷰(:30)와
     * 15분씩, 그리고 같은 아웃박스 전표를 다투는 안전망(01:45)과 <b>30분</b> 떨어지도록 고른 값이다.
     */
    @Test
    @DisplayName("북마크 델타 cron 플레이스홀더는 프로퍼티가 없어도 매시 15분으로 해석된다")
    void bookmarkDeltaCronPlaceholderFallsBackToHourlyQuarterPast() throws Exception {
        String resolved = resolvedCron("consumeBookmarkCountDeltas");

        LocalDateTime first =
                CronExpression.parse(resolved).next(LocalDateTime.of(2026, 7, 30, 0, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 7, 30, 0, 15));
        assertThat(CronExpression.parse(resolved).next(first))
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 1, 15));

        assertThat(new PlaceStatsProperties().getBookmarkDeltaCron()).isEqualTo(resolved);
    }

    /**
     * 안전망 회차도 <b>하루 1회</b>이고, 점수 회차와 같은 이유로 시각이 못 박혀 있다.
     * 특히 매시 회차(01:30)와 겹치면 아웃박스 전표를 두고 서로를 기다린다.
     */
    @Test
    @DisplayName("안전망 cron 플레이스홀더는 프로퍼티가 없어도 매일 01:45로 해석된다")
    void countSafetyCronPlaceholderFallsBackToDailyOneFortyFive() throws Exception {
        String resolved = resolvedCron("recalculatePlaceCountsSafety");

        LocalDateTime first =
                CronExpression.parse(resolved).next(LocalDateTime.of(2026, 7, 30, 0, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 7, 30, 1, 45));
        assertThat(CronExpression.parse(resolved).next(first))
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 1, 45));

        assertThat(new PlaceStatsProperties().getCountSafetyCron()).isEqualTo(resolved);
    }

    /**
     * 점수 회차는 <b>하루 1회</b>여야 한다. 다음 실행을 두 번 보는 이유가 카운트 쪽과 정반대다 —
     * 여기서는 두 회가 24시간 간격임을 봐야 "매시 01분"류의 회귀가 걸린다.
     */
    @Test
    @DisplayName("점수 cron 플레이스홀더는 프로퍼티가 없어도 매일 01:00으로 해석된다")
    void scoreCronPlaceholderFallsBackToDailyOneAm() throws Exception {
        String resolved = resolvedCron("recalculatePopularScores");

        LocalDateTime first =
                CronExpression.parse(resolved).next(LocalDateTime.of(2026, 7, 30, 0, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 7, 30, 1, 0));
        assertThat(CronExpression.parse(resolved).next(first))
                .isEqualTo(LocalDateTime.of(2026, 7, 31, 1, 0));

        assertThat(new PlaceStatsProperties().getScoreCron()).isEqualTo(resolved);
    }

    /**
     * <b>두 회차의 시간대는 KST 하나로 통일돼 있다.</b> 점수 배치의 01:00을 그렇게 고른 근거가
     * "국내 트래픽 최저 시각"이라 서버 시간대와 무관하게 매여 있어야 하고, 카운트 배치도 같은 값을
     * 달아야 아래 30분 간격이 <em>같은 시계 위에서</em> 성립한다.
     *
     * <p>{@code TimezoneConfig}가 JVM 기본을 이미 KST로 고정하지만, 그 설정이 사라져도 이 두 배치는
     * 흔들리지 않아야 한다. <b>한쪽만 비어 있는 상태를 특히 겨눈다</b> — 그러면 있지도 않은
     * 비대칭("카운트는 서버 시간대, 점수는 KST")이 코드에 남는다.
     */
    @Test
    void 네_배치의_시간대는_모두_KST로_고정돼_있다() throws Exception {
        for (String method : SCHEDULED_METHODS) {
            assertThat(PlaceStatsFacade.class.getMethod(method)
                    .getAnnotation(Scheduled.class).zone())
                    .as("%s의 zone", method)
                    .isEqualTo("Asia/Seoul");
        }
    }

    /**
     * 회차마다 락 이름이 갈려 있어야 한다. 같은 이름을 공유하면 정시에 겹친 회차 중 하나가
     * <b>통째로 건너뛰어진다</b> — 실제 동작은 {@code PlaceStatsSchedulerLockIT}가 물고,
     * 여기서는 이름이 네 개로 갈려 있다는 사실 자체를 고정한다.
     */
    @Test
    void 네_배치의_락_이름은_서로_다르다() throws Exception {
        List<String> lockNames = new ArrayList<>();
        for (String method : SCHEDULED_METHODS) {
            lockNames.add(PlaceStatsFacade.class.getMethod(method)
                    .getAnnotation(SchedulerLock.class).name());
        }

        assertThat(lockNames).doesNotHaveDuplicates();
        assertThat(lockNames).containsExactly(
                "place-stats-count", "place-stats-bookmark-delta",
                "place-stats-count-safety", "place-stats-score");
    }

    /**
     * <b>네 회차가 같은 시각에 겹치지 않는다.</b> 점수 문장은 {@code place_stats} 전 행에 X 락을
     * 커밋까지 들고, 안전망과 델타 소비는 같은 아웃박스 전표를 {@code FOR UPDATE}로 잡는다 —
     * 겹치면 서로를 기다린다. 01:00(점수) · 01:15(델타) · 01:30(리뷰) · 01:45(안전망)가 그 간격을
     * 만들고, 한쪽 cron만 고쳐 같은 분으로 옮기는 회귀를 여기서 잡는다.
     *
     * <p><b>매시 회차가 둘이 되면서 전 쌍 비교로 넓혔다.</b> 셋일 때는 "매시 하나 대 새벽 둘"이라
     * 매시끼리의 충돌이 없었는데, 지금은 :15와 :30이 서로 겹칠 수 있다.
     */
    @Test
    void 네_배치의_발화_시각은_한_쌍도_겹치지_않는다() throws Exception {
        LocalDateTime from = LocalDateTime.of(2026, 7, 30, 0, 0);
        List<CronExpression> crons = new ArrayList<>();
        for (String method : SCHEDULED_METHODS) {
            crons.add(CronExpression.parse(resolvedCron(method)));
        }

        for (int i = 0; i < crons.size(); i++) {
            for (int j = 0; j < crons.size(); j++) {
                if (i == j) {
                    continue;
                }
                // j 회차가 발화하는 어느 시각에도 i 회차가 같이 발화하지 않는다.
                // 하루치를 다 훑는 이유: 매시 회차는 발화가 24번이라 한 시각만 보면 못 잡는다.
                LocalDateTime fire = crons.get(j).next(from);
                while (fire != null && fire.isBefore(from.plusDays(1))) {
                    LocalDateTime other = crons.get(i).next(fire.minusSeconds(1));
                    assertThat(other)
                            .as("%s와 %s가 %s에 함께 발화한다",
                                    SCHEDULED_METHODS.get(i), SCHEDULED_METHODS.get(j), fire)
                            .isNotEqualTo(fire);
                    fire = crons.get(j).next(fire);
                }
            }
        }
    }

    /** 락 이름이 갈려 있어야 두 회차가 서로의 락을 잡아먹지 않는다 — 실제 동작은 SchedulerLockIT가 문다 */
    private String resolvedCron(String methodName) throws Exception {
        String cronExpression = PlaceStatsFacade.class
                .getMethod(methodName)
                .getAnnotation(Scheduled.class)
                .cron();
        // 기본값이 없으면 여기서 IllegalArgumentException으로 터진다 (= 기동 실패와 같은 지점)
        return new StandardEnvironment().resolveRequiredPlaceholders(cronExpression);
    }

    // === 부팅 최초 적재 ===

    /**
     * 부팅 진입점은 반드시 "비었을 때만" 경로를 타야 한다. 무조건 재계산으로 바꾸면 롤링 배포마다
     * 전량 재계산이 돌고 그 동안 {@code places} FK S 락으로 어드민 쓰기가 막힌다.
     *
     * <p><b>순서가 계약이다 — 카운트가 행을 만들어야 점수가 갱신할 대상이 생긴다.</b> 뒤집으면
     * 채점이 0행을 갱신하고 끝나 다음 새벽까지 전 장소가 0점으로 서빙된다.
     */
    @Test
    void 부팅_적재는_카운트를_먼저_채우고_그_다음에_채점한다() {
        given(batchProcessor.recalculateCountsIfEmpty(any(LocalDateTime.class)))
                .willReturn(OptionalInt.of(10));
        given(batchProcessor.recalculateScoresIfNeverScored(any(LocalDateTime.class)))
                .willReturn(OptionalInt.of(10));

        placeStatsFacade.backfillPlaceStatsOnStartup();

        InOrder order = inOrder(batchProcessor);
        order.verify(batchProcessor).recalculateCountsIfEmpty(any(LocalDateTime.class));
        order.verify(batchProcessor).recalculateScoresIfNeverScored(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateCounts(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateScores(any(LocalDateTime.class));
    }

    /**
     * <b>카운트가 실패해도 채점은 시도한다.</b> 이전 배포가 남긴 행이 있을 수 있고, 무엇보다 두
     * 단계를 한 {@code try}로 묶으면 카운트 실패가 채점을 통째로 삼켜 "0점 서빙"이 조용히 남는다.
     */
    @Test
    void 부팅_카운트_적재가_실패해도_채점은_시도한다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateCountsIfEmpty(any(LocalDateTime.class));
        given(batchProcessor.recalculateScoresIfNeverScored(any(LocalDateTime.class)))
                .willReturn(OptionalInt.of(10));

        placeStatsFacade.backfillPlaceStatsOnStartup();

        verify(batchProcessor).recalculateScoresIfNeverScored(any(LocalDateTime.class));
    }

    /**
     * 생략 분기에서 파사드는 "완료"를 주장하면 안 된다. 생략 로그 본문(기존 행 수 포함)은
     * 그 수치를 아는 {@code PlaceStatsBatchProcessor}가 찍고,
     * {@code PlaceStatsBatchProcessorIT.이미_채워져_있으면_카운트_최초_적재는_다시_돌지_않는다}가 검증한다.
     * 여기서는 파사드가 그 위에 잘못된 완료 로그를 덧씌우지 않는다는 것만 못 박는다.
     */
    @Test
    void 부팅_적재를_건너뛰면_완료_로그를_남기지_않는다() {
        given(batchProcessor.recalculateCountsIfEmpty(any(LocalDateTime.class)))
                .willReturn(OptionalInt.empty());
        given(batchProcessor.recalculateScoresIfNeverScored(any(LocalDateTime.class)))
                .willReturn(OptionalInt.empty());

        placeStatsFacade.backfillPlaceStatsOnStartup();

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("완료"));
    }

    /**
     * 최초 적재가 터져도 기동 자체는 계속돼야 한다. 여기서 예외가 새면
     * {@code ApplicationReadyEvent} 발행이 실패해 애플리케이션이 뜨지 않는다.
     */
    @Test
    void 부팅_적재가_실패해도_예외를_흘리지_않고_error로_남긴다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateCountsIfEmpty(any(LocalDateTime.class));
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateScoresIfNeverScored(any(LocalDateTime.class));

        placeStatsFacade.backfillPlaceStatsOnStartup();

        // 두 단계가 각자 잡으므로 error도 둘이다 — 어느 쪽이 죽었는지 로그로 갈려야 한다
        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.getThrowableProxy().getMessage())
                        .isEqualTo("boom"));
    }

    /**
     * {@code PlaceStatsFacade}에 {@code @Transactional}이 붙는 순간 프로세서가 이 트랜잭션에
     * <em>참여</em>해 {@code READ_COMMITTED} 지정이 조용히 버려지고, 배치가 {@code bookmarks}
     * 전 행에 next-key 락을 걸게 된다. 클래스·메서드 어디에도 붙지 않았음을 못 박는다.
     */
    @Test
    void 파사드에는_트랜잭션_어노테이션이_붙어_있지_않다() throws Exception {
        assertThat(PlaceStatsFacade.class.getAnnotation(Transactional.class)).isNull();
        for (String method : SCHEDULED_METHODS) {
            assertThat(PlaceStatsFacade.class.getMethod(method)
                    .getAnnotation(Transactional.class))
                    .as("%s의 @Transactional", method)
                    .isNull();
        }
        assertThat(PlaceStatsFacade.class.getMethod("backfillPlaceStatsOnStartup")
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("예외를 삼키더라도 원인을 담아 error 레벨로 남긴다")
    void logsFailureAtErrorLevelWithCause() {
        RuntimeException cause = new RuntimeException("boom");
        willThrow(cause).given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculateReviewCounts();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
                });
    }

    /**
     * 시작 로그는 프로세서 호출 <b>전에</b> 나가야 한다.
     *
     * <p>다중 인스턴스에서 뒤에 온 배치는 앞 배치가 커밋될 때까지 {@code place_stats} PK 첫 행에서
     * 블록된다(최대 {@code innodb_lock_wait_timeout} 50초). 시작 로그가 없거나 호출 <em>뒤에</em>
     * 있으면 "배치가 매달려 있다"와 "스케줄이 애초에 안 돌았다"가 로그로 구분되지 않는다.
     *
     * <p>축이 즉시 던지게 만들어 두면, 시작 로그가 남아 있다는 사실 자체가
     * "호출 전에 찍혔다"의 증거가 된다.
     *
     * <p><b>2026-09-12부터 매시 회차의 INFO는 두 줄이다</b> — 시작 줄과 회차 종료 줄. 그래서
     * {@code singleElement()}가 아니라 <b>첫 INFO가 시작 줄</b>인지를 본다(순서가 곧 "호출 전"이다).
     */
    @Test
    @DisplayName("배치 실패 시에도 시작 로그가 먼저 남아 있다")
    void logsStartBeforeInvokingProcessor() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculateReviewCounts();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .first()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("시작"));
    }

    // === 회차 종료 로그 ===

    /**
     * <b>회차 종료 줄은 시도별 완료 줄과 재는 구간이 다르다</b> — 저쪽은 성공한 시도 하나의 소요라
     * 앞선 실패와 재시도 대기가 어디에도 남지 않았다. 종료 줄의 {@code elapsed}가
     * {@code lockAtMostFor}·회차 간격과 비교할 수 있는 유일한 수치다.
     *
     * <p>성공한 회차에서 시작 줄과 종료 줄이 <b>짝</b>을 이루는지 본다 — 짝 없는 시작 줄은
     * "회차가 끝나지 않았다"는 신호이므로, 짝이 성립해야 그 신호가 뜻을 갖는다.
     */
    @Test
    void 매시_두_회차는_성공하면_결과_성공의_종료_로그를_남긴다() {
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        assertThat(infoMessages())
                .anyMatch(m -> m.contains("인기순 리뷰 카운트 회차 종료") && m.contains("결과=성공"))
                .anyMatch(m -> m.contains("북마크 카운트 델타 회차 종료") && m.contains("결과=성공"));
        assertThat(infoMessages()).filteredOn(m -> m.contains("시작")).hasSize(2);
        assertThat(infoMessages()).filteredOn(m -> m.contains("회차 종료")).hasSize(2);
    }

    /**
     * <b>실패한 회차에도 종료 줄이 남는다.</b> 남지 않으면 "끝내 실패한 회차"와 "아직 도는 회차"가
     * 로그에서 같아 보인다. 레벨이 {@code info}인 것도 계약이다 — 원인과 스택은 {@code runWithRetry}가
     * 이미 {@code error}로 냈고, 여기서 한 번 더 올리면 회차 하나가 알림을 두 번 울린다.
     */
    @Test
    void 매시_두_회차는_실패해도_결과_실패의_종료_로그를_info로_남긴다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        willThrow(new RuntimeException("boom")).given(deltaProcessor).consumeAndApply();

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        assertThat(infoMessages())
                .anyMatch(m -> m.contains("인기순 리뷰 카운트 회차 종료") && m.contains("결과=실패"))
                .anyMatch(m -> m.contains("북마크 카운트 델타 회차 종료") && m.contains("결과=실패"));
        // 실패는 error로만 울린다 — 종료 줄이 두 번째 경보가 되지 않는다
        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .hasSize(2);
    }

    /**
     * <b>새벽 두 회차에는 종료 줄이 없다.</b> 2026-09-12 분리에서 동작을 바꾸지 않기로 한 부분이고,
     * 그 선택이 의도였음을 여기서 고정한다 — 나중에 붙인다면 그것은 결정이지 사고가 아니어야 한다.
     */
    @Test
    void 새벽_두_회차는_종료_로그를_남기지_않는다() {
        given(batchProcessor.recalculateCountsAndClearOutbox(any(LocalDateTime.class)))
                .willReturn(10);
        given(batchProcessor.recalculateScores(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculatePlaceCountsSafety();
        placeStatsFacade.recalculatePopularScores();

        assertThat(infoMessages()).noneMatch(m -> m.contains("회차 종료"));
    }

    private java.util.List<String> infoMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // === 카운트 안전망 회차 ===

    /**
     * <b>안전망은 재계산과 아웃박스 비우기를 묶은 진입점 하나로만 돈다.</b> 여기서
     * {@code recalculateCounts}(비우지 않는 쪽)를 부르면 이미 셈에 들어간 토글을 다음 델타 회차가
     * 또 더한다 — 값이 조용히 어긋나고 오류도 로그도 없다.
     */
    @Test
    void 안전망_회차는_아웃박스를_비우는_전량_재계산을_부른다() {
        given(batchProcessor.recalculateCountsAndClearOutbox(any(LocalDateTime.class)))
                .willReturn(10);

        placeStatsFacade.recalculatePlaceCountsSafety();

        verify(batchProcessor).recalculateCountsAndClearOutbox(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateCounts(any(LocalDateTime.class));
        verify(deltaProcessor, never()).consumeAndApply();
    }

    /** 회차 간격이 하루라 여기서도 재시도가 값어치 있다 — 한 번 죽으면 표류 상한이 이틀이 된다 */
    @Test
    void 안전망_회차도_실패하면_최대_시도까지_재시도한다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateCountsAndClearOutbox(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceCountsSafety();

        verify(batchProcessor, times(placeStatsProperties.getBatchMaxAttempts()))
                .recalculateCountsAndClearOutbox(any(LocalDateTime.class));
    }
}
