package org.sopt.solply_server.domain.place.service.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
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

    @Test
    @DisplayName("배치 실행 시 하나의 기준 시각으로 프로세서를 1회 호출한다")
    void runsBatchOnceWithSingleTimestamp() {
        given(batchProcessor.recalculateAll(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculatePlaceStats();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchProcessor).recalculateAll(captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("프로세서가 실패해도 스케줄러 스레드로 예외를 던지지 않는다")
    void swallowsProcessorFailure() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateAll(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceStats();

        verify(batchProcessor).recalculateAll(any(LocalDateTime.class));
    }

    /**
     * {@code application.yml}은 {@code .gitignore}에 걸려 커밋되지 않는다. 즉 yml이 없는 환경
     * (신규 클론·CI)에서는 {@code solply.place-stats.cron} 키가 <b>존재하지 않는다</b>.
     * 플레이스홀더에 기본값이 없으면 그 환경은 스케줄러 초기화 단계에서 기동이 통째로 실패한다.
     *
     * <p>로컬 yml에 키가 있으면 {@code contextLoads}는 이 결함을 절대 못 잡는다 — 실제로 현재
     * 로컬 yml에 {@code cron: "0 0 2 * * *"}이 있어 컨텍스트 테스트는 기본값 경로를 밟지 않는다.
     * 그래서 키가 전혀 없는 빈 Environment로 직접 해석해 본다.
     */
    @Test
    @DisplayName("cron 플레이스홀더는 프로퍼티가 없어도 매일 02:00으로 해석된다")
    void cronPlaceholderFallsBackToDailyTwoAm() throws Exception {
        String cronExpression = PlaceStatsFacade.class
                .getMethod("recalculatePlaceStats")
                .getAnnotation(Scheduled.class)
                .cron();

        // 기본값이 없으면 여기서 IllegalArgumentException으로 터진다 (= 기동 실패와 같은 지점)
        String resolved = new StandardEnvironment().resolveRequiredPlaceholders(cronExpression);

        assertThat(CronExpression.parse(resolved).next(LocalDateTime.of(2026, 7, 30, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 2, 0));
    }

    /**
     * 부팅 진입점은 반드시 {@code recalculateIfEmpty}를 타야 한다. {@code recalculateAll}로 바꾸면
     * 롤링 배포마다 전량 재계산이 돌고 그 동안 {@code places} FK S 락으로 어드민 쓰기가 막힌다.
     */
    @Test
    void 부팅_적재는_비었을때만_실행_경로를_호출한다() {
        given(batchProcessor.recalculateIfEmpty(any(LocalDateTime.class)))
                .willReturn(OptionalInt.of(10));

        placeStatsFacade.backfillPlaceStatsOnStartup();

        verify(batchProcessor).recalculateIfEmpty(any(LocalDateTime.class));
        verify(batchProcessor, never()).recalculateAll(any(LocalDateTime.class));
    }

    @Test
    void 부팅_적재를_건너뛰면_생략_로그를_남긴다() {
        given(batchProcessor.recalculateIfEmpty(any(LocalDateTime.class)))
                .willReturn(OptionalInt.empty());

        placeStatsFacade.backfillPlaceStatsOnStartup();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("생략"));
    }

    /**
     * 최초 적재가 터져도 기동 자체는 계속돼야 한다. 여기서 예외가 새면
     * {@code ApplicationReadyEvent} 발행이 실패해 애플리케이션이 뜨지 않는다.
     */
    @Test
    void 부팅_적재가_실패해도_예외를_흘리지_않고_error로_남긴다() {
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateIfEmpty(any(LocalDateTime.class));

        placeStatsFacade.backfillPlaceStatsOnStartup();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> assertThat(event.getThrowableProxy().getMessage())
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
        assertThat(PlaceStatsFacade.class.getMethod("recalculatePlaceStats")
                .getAnnotation(Transactional.class)).isNull();
        assertThat(PlaceStatsFacade.class.getMethod("backfillPlaceStatsOnStartup")
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("예외를 삼키더라도 원인을 담아 error 레벨로 남긴다")
    void logsFailureAtErrorLevelWithCause() {
        RuntimeException cause = new RuntimeException("boom");
        willThrow(cause).given(batchProcessor).recalculateAll(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceStats();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> {
                    // 스택트레이스가 빠지면 "실패했다"만 알고 왜인지는 영영 모른다
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
                .given(batchProcessor).recalculateAll(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceStats();

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("시작"));
    }
}
