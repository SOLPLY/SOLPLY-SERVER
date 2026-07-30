package org.sopt.solply_server.domain.place.service.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDateTime;
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

    @Test
    @DisplayName("예외를 삼키더라도 원인을 담아 error 레벨로 남긴다")
    void logsFailureAtErrorLevelWithCause() {
        RuntimeException cause = new RuntimeException("boom");
        willThrow(cause).given(batchProcessor).recalculateAll(any(LocalDateTime.class));

        placeStatsFacade.recalculatePlaceStats();

        assertThat(logAppender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    // 스택트레이스가 빠지면 "실패했다"만 알고 왜인지는 영영 모른다
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
                });
    }
}
