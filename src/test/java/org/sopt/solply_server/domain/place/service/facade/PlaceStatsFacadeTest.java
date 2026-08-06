package org.sopt.solply_server.domain.place.service.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.sopt.solply_server.domain.place.config.PlaceStatsProperties;
import org.sopt.solply_server.domain.place.service.PlaceStatsBatchProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PlaceStatsFacadeTest {

    @Mock
    private PlaceStatsBatchProcessor batchProcessor;

    @InjectMocks
    private PlaceStatsFacade placeStatsFacade;

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

    // === 카운트 회차 ===

    @Test
    @DisplayName("카운트 배치는 하나의 기준 시각으로 프로세서를 1회 호출한다")
    void runsCountBatchOnceWithSingleTimestamp() {
        given(batchProcessor.recalculateCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculatePlaceCounts();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchProcessor).recalculateCounts(captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    /**
     * <b>두 회차는 서로의 진입점을 부르지 않는다.</b> 한쪽이 다른 쪽까지 돌리면 주기를 가른 의미가
     * 사라지고(카운트 배치가 매시 점수를 다시 계산한다), 무엇보다 두 배치가 같은 시각에 겹쳐
     * {@code place_stats} 전 행 X 락을 두고 서로를 기다린다.
     */
    @Test
    void 카운트_배치는_점수를_건드리지_않는다() {
        given(batchProcessor.recalculateCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculatePlaceCounts();

        verify(batchProcessor, never()).recalculateScores(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("카운트 프로세서가 실패해도 스케줄러 스레드로 예외를 던지지 않는다")
    void swallowsCountProcessorFailure() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceCounts();

        verify(batchProcessor).recalculateCounts(any(LocalDateTime.class));
    }

    // === 점수 회차 ===

    @Test
    void 점수_배치는_카운트를_건드리지_않는다() {
        given(batchProcessor.recalculateScores(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculatePopularScores();

        verify(batchProcessor).recalculateScores(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateCounts(any(LocalDateTime.class));
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
    @DisplayName("카운트 cron 플레이스홀더는 프로퍼티가 없어도 매시 30분으로 해석된다")
    void countCronPlaceholderFallsBackToHourlyHalfPast() throws Exception {
        String resolved = resolvedCron("recalculatePlaceCounts");

        LocalDateTime first =
                CronExpression.parse(resolved).next(LocalDateTime.of(2026, 7, 30, 0, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 7, 30, 0, 30));
        assertThat(CronExpression.parse(resolved).next(first))
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 1, 30));

        assertThat(new PlaceStatsProperties().getCountCron()).isEqualTo(resolved);
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
    void 두_배치의_시간대는_모두_KST로_고정돼_있다() throws Exception {
        assertThat(PlaceStatsFacade.class.getMethod("recalculatePlaceCounts")
                .getAnnotation(Scheduled.class).zone()).isEqualTo("Asia/Seoul");
        assertThat(PlaceStatsFacade.class.getMethod("recalculatePopularScores")
                .getAnnotation(Scheduled.class).zone()).isEqualTo("Asia/Seoul");
    }

    /**
     * <b>두 회차가 같은 시각에 겹치지 않는다.</b> 점수 문장이 {@code place_stats} 전 행에 X 락을
     * 커밋까지 들고 있어, 겹치면 두 배치가 서로를 기다린다. 01:00(점수)과 01:30(카운트)이
     * 그 간격을 만든다 — 한쪽 cron만 고쳐 정각으로 옮기는 회귀를 여기서 잡는다.
     */
    @Test
    void 두_배치의_발화_시각은_겹치지_않는다() throws Exception {
        CronExpression count = CronExpression.parse(resolvedCron("recalculatePlaceCounts"));
        CronExpression score = CronExpression.parse(resolvedCron("recalculatePopularScores"));

        LocalDateTime scoreFire = score.next(LocalDateTime.of(2026, 7, 30, 0, 0));
        // 점수 회차 직전의 카운트 발화와 직후의 카운트 발화 어느 쪽도 같은 분에 걸리지 않는다
        assertThat(count.next(scoreFire)).isNotEqualTo(scoreFire);
        assertThat(count.next(scoreFire.minusSeconds(1))).isNotEqualTo(scoreFire);
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
        assertThat(PlaceStatsFacade.class.getMethod("recalculatePlaceCounts")
                .getAnnotation(Transactional.class)).isNull();
        assertThat(PlaceStatsFacade.class.getMethod("recalculatePopularScores")
                .getAnnotation(Transactional.class)).isNull();
        assertThat(PlaceStatsFacade.class.getMethod("backfillPlaceStatsOnStartup")
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("예외를 삼키더라도 원인을 담아 error 레벨로 남긴다")
    void logsFailureAtErrorLevelWithCause() {
        RuntimeException cause = new RuntimeException("boom");
        willThrow(cause).given(batchProcessor).recalculateCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceCounts();

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
     * <p>프로세서가 즉시 던지게 만들어 두면, 시작 로그가 남아 있다는 사실 자체가
     * "호출 전에 찍혔다"의 증거가 된다.
     */
    @Test
    @DisplayName("배치 실패 시에도 시작 로그가 먼저 남아 있다")
    void logsStartBeforeInvokingProcessor() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateCounts(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceCounts();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("시작"));
    }
}
