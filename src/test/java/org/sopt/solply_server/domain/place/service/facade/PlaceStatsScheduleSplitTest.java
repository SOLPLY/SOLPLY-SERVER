package org.sopt.solply_server.domain.place.service.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * 2026-09-12 회차 분리가 실제로 <b>갈렸는지</b>만 본다 — 리뷰 축(:30)과 북마크 델타(:15)가 각자의
 * cron 키·락 이름·재시도 설정을 따르는가, 그리고 각 회차가 소요를 남기고 끝나는가.
 *
 * <p><b>{@code PlaceStatsFacadeTest}와 파일을 가른 이유</b>는 검증의 성질이 다르기 때문이다.
 * 저쪽은 "한 회차가 무엇을 부르는가"를 축마다 보고, 여기는 <b>두 회차 사이의 관계</b>만 본다 —
 * 한쪽 키를 건드렸을 때 다른 쪽이 따라 움직이는지가 전부 이 파일의 단언이다. 분리를 되돌리는
 * 회귀(두 축이 다시 한 키·한 락을 공유하는 모양)는 축 하나만 보는 테스트로는 전부 그린이다.
 *
 * <p><b>소요 단언은 하한만 건다.</b> 벽시계 정확값을 기대하면 CI의 스케줄링 지터로 흔들린다.
 * 여기서 지키려는 명제는 "회차 종료 로그의 {@code elapsed}가 실패한 시도와 재시도 대기를 포함한
 * 회차 전체다"이고, 그것은 <b>대기의 합보다 크다</b>는 부등식으로 충분히 고정된다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceStatsScheduleSplitTest {

    /** 재시도 대기의 존재를 소요 하한으로 드러내는 값. 테스트를 잠재우지 않을 만큼 짧게 잡는다. */
    private static final Duration RETRY_DELAY = Duration.ofMillis(60);

    /**
     * {@code Thread.sleep(60)}을 두 번 거친 회차의 소요 하한. 60 × 2 = 120이 아니라 100인 이유는
     * 종료 로그가 {@code Duration#toMillis}로 <b>내림</b>하기 때문이다 — 경계에 딱 붙이면 반올림
     * 한 칸에 빨간불이 켜진다. 하한의 용도는 "대기가 회차 소요에 들어갔다"를 가르는 것이고,
     * 대기가 빠진 회차는 한 자릿수 ms라 100으로도 갈린다.
     */
    private static final long TWO_WAITS_LOWER_BOUND_MS = 100;

    /** 한 번의 대기만 거친 회차의 하한. 근거는 위와 같다. */
    private static final long ONE_WAIT_LOWER_BOUND_MS = 50;

    private static final Pattern ELAPSED = Pattern.compile("elapsed=(\\d+)ms");

    @Mock
    private PlaceStatsBatchProcessor batchProcessor;

    @Mock
    private BookmarkCountDeltaProcessor deltaProcessor;

    /**
     * 실물을 쓰는 것이 이 파일의 요점이다 — 검증 대상이 <b>기본값과 키의 배선</b>이라
     * 목으로 바꾸면 모든 단언이 스텁을 되읽는 동어반복이 된다.
     */
    private final PlaceStatsProperties placeStatsProperties = new PlaceStatsProperties();

    private PlaceStatsFacade placeStatsFacade;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger facadeLogger;

    @BeforeEach
    void createFacade() {
        placeStatsFacade = new PlaceStatsFacade(
                batchProcessor, deltaProcessor, placeStatsProperties);
    }

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

    // === 회차마다 다른 cron ===

    /**
     * 리뷰 축은 <b>옛 통합 회차의 키와 시각을 그대로 물려받았다</b>. 배포 환경마다 yml이 따로 있고
     * 그 파일은 저장소에 없으므로, 키를 옮기면 각 환경의 yml을 손대기 전까지 옛 키가 조용히
     * 죽은 키가 된다. 그 승계가 실제로 유지되는지를 여기서 본다.
     *
     * <p>다음 발화를 두 번 보는 이유는 {@code PlaceStatsFacadeTest}의 cron 테스트들과 같다 —
     * 한 번만 보면 "매일 00:30"도 통과한다.
     */
    @Test
    @DisplayName("리뷰 축 cron은 프로퍼티가 없어도 매시 30분으로 해석된다")
    void 리뷰_축_cron은_프로퍼티가_없어도_매시_30분이다() throws Exception {
        String resolved = resolvedCron("recalculateReviewCounts");

        LocalDateTime first =
                CronExpression.parse(resolved).next(LocalDateTime.of(2026, 9, 12, 0, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 9, 12, 0, 30));
        assertThat(CronExpression.parse(resolved).next(first))
                .isEqualTo(LocalDateTime.of(2026, 9, 12, 1, 30));

        assertThat(new PlaceStatsProperties().getCountCron()).isEqualTo(resolved);
    }

    /**
     * 북마크 축의 기본값은 <b>신규 키</b>다. yml에 이 키를 넣지 않은 환경(= 저장소를 새로 클론한
     * 모든 환경)이 :15에 돌아야 하므로 플레이스홀더의 기본값이 유일한 방어선이다.
     *
     * <p>{@code PlaceStatsProperties} 필드와 묶어 보는 것도 리뷰 축과 같은 이유다 — 기본값 리터럴이
     * 두 곳에 있는 것이 구조적으로 강제된 중복이라, 한쪽만 고치면 스케줄과 검증값이 조용히 갈린다.
     */
    @Test
    @DisplayName("북마크 델타 cron은 프로퍼티가 없어도 매시 15분으로 해석된다")
    void 북마크_델타_cron은_프로퍼티가_없어도_매시_15분이다() throws Exception {
        String resolved = resolvedCron("consumeBookmarkCountDeltas");

        LocalDateTime first =
                CronExpression.parse(resolved).next(LocalDateTime.of(2026, 9, 12, 0, 0));
        assertThat(first).isEqualTo(LocalDateTime.of(2026, 9, 12, 0, 15));
        assertThat(CronExpression.parse(resolved).next(first))
                .isEqualTo(LocalDateTime.of(2026, 9, 12, 1, 15));

        assertThat(new PlaceStatsProperties().getBookmarkDeltaCron()).isEqualTo(resolved);
    }

    /**
     * <b>두 축이 서로 다른 프로퍼티 키를 읽는다는 것 자체가 계약이다.</b> 위의 두 테스트는 기본값이
     * 같아지는 회귀만 잡는다 — 북마크 축이 {@code count-cron}을 읽도록 되돌아가도 기본값이 :30이
     * 되어 거기서는 "매시 15분이 아니다"로 걸리지만, 반대로 두 리터럴이 우연히 맞아떨어지면
     * <b>yml로 주기를 따로 잡을 자유</b>가 사라진 것을 아무도 못 본다. 그 자유가 분리로 얻은 둘 중
     * 하나이므로 키 문자열을 직접 못 박는다.
     */
    @Test
    void 두_축은_서로_다른_프로퍼티_키를_읽는다() throws Exception {
        assertThat(rawCron("recalculateReviewCounts"))
                .contains("solply.place-stats.count-cron");
        assertThat(rawCron("consumeBookmarkCountDeltas"))
                .contains("solply.place-stats.bookmark-delta-cron");
        assertThat(rawCron("consumeBookmarkCountDeltas"))
                .doesNotContain("solply.place-stats.count-cron");
    }

    /**
     * <b>:15를 고른 근거를 그대로 단언으로 옮긴다.</b> 이 회차와 01:45 안전망은 같은 아웃박스
     * 전표를 {@code FOR UPDATE}로 잡는 짝이라 서로 가장 멀어야 하고, 분리로 그 간격이 15분에서
     * 30분이 됐다. 리뷰 축과의 15분은 매시 두 축이 같은 스케줄러 스레드를 나눠 쓰기 때문에 필요한
     * 여유다(격리가 아니라 여유라는 것은 {@code PlaceStatsFacade} javadoc에 적혀 있다).
     *
     * <p>한쪽 cron만 옮겨 두 축이 같은 분에 겹치는 회귀가 이 단언의 표적이다.
     */
    @Test
    void 북마크_축은_리뷰_축과_15분_안전망과_30분_떨어져_있다() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 9, 12, 1, 0);
        LocalDateTime bookmarkFire =
                CronExpression.parse(resolvedCron("consumeBookmarkCountDeltas")).next(base);
        LocalDateTime reviewFire =
                CronExpression.parse(resolvedCron("recalculateReviewCounts")).next(base);
        LocalDateTime safetyFire =
                CronExpression.parse(resolvedCron("recalculatePlaceCountsSafety")).next(base);

        assertThat(Duration.between(bookmarkFire, reviewFire)).isEqualTo(Duration.ofMinutes(15));
        assertThat(Duration.between(bookmarkFire, safetyFire)).isEqualTo(Duration.ofMinutes(30));
    }

    // === 회차마다 다른 락 ===

    /**
     * <b>락 이름이 같으면 겹친 시각에 깨어난 한쪽이 통째로 건너뛰어진다.</b> 그 회차의 축은 한
     * 시간 낡은 값을 그대로 들고 있게 되고, 로그에는 실패가 아니라 <em>아무것도</em> 남지 않는다.
     *
     * <p>리뷰 축이 옛 이름을 지키는 것도 함께 못 박는다. {@code shedlock}의 그 한 행이
     * "배치가 마지막으로 돈 시각"을 읽는 지점이라(V35) 개명하면 그 관찰이 끊긴다.
     *
     * <p>실제로 프록시가 걸려 두 번째 호출이 건너뛰어지는지는 {@code PlaceStatsSchedulerLockIT}가
     * 문다. 여기서 보는 것은 <b>이름이 갈려 있는가</b>뿐이라 어노테이션만 읽는다.
     */
    @Test
    void 네_회차의_락_이름은_모두_다르고_리뷰_축은_옛_이름을_지킨다() throws Exception {
        assertThat(lockName("recalculateReviewCounts")).isEqualTo("place-stats-count");
        assertThat(lockName("consumeBookmarkCountDeltas")).isEqualTo("place-stats-bookmark-delta");

        assertThat(List.of(
                lockName("recalculateReviewCounts"),
                lockName("consumeBookmarkCountDeltas"),
                lockName("recalculatePlaceCountsSafety"),
                lockName("recalculatePopularScores")))
                .doesNotHaveDuplicates();
    }

    // === 회차마다 다른 재시도 설정 ===

    /**
     * <b>한 축의 시도 횟수를 줄여도 다른 축은 제 값을 따른다.</b> 두 축이 실패 때 남기는 것이
     * 다르기 때문에 갈라 둔 설정이다 — 리뷰 축의 실패는 낡은 값을 남기고 다음 회차가 덮어쓰지만,
     * 북마크 축은 실패한 시도가 전표를 소비하지 않고 롤백해 <b>일감이 다음 회차로 쌓인다</b>.
     *
     * <p>한 회차만 재시도를 끄는 것이 이 테스트의 모양인 이유는, 공용 키로 되돌아간 회귀가
     * 정확히 이 지점에서만 드러나기 때문이다 — 두 축에 같은 값을 주면 어느 키를 읽든 그린이다.
     */
    @Test
    void 리뷰_축의_시도_횟수를_줄여도_북마크_축은_제_설정을_따른다() {
        placeStatsProperties.setReviewCountMaxAttempts(1);
        placeStatsProperties.setReviewCountRetryDelay(Duration.ZERO);
        placeStatsProperties.setBookmarkDeltaRetryDelay(Duration.ZERO);
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        given(deltaProcessor.consumeAndApply()).willThrow(new RuntimeException("boom"));

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(batchProcessor, times(1)).recalculateReviewCounts(any(LocalDateTime.class));
        verify(deltaProcessor, times(3)).consumeAndApply();
    }

    /** 위 테스트의 반대 방향 — 어느 쪽을 줄이든 상대는 흔들리지 않아야 대칭이 성립한다. */
    @Test
    void 북마크_축의_시도_횟수를_줄여도_리뷰_축은_제_설정을_따른다() {
        placeStatsProperties.setBookmarkDeltaMaxAttempts(1);
        placeStatsProperties.setReviewCountRetryDelay(Duration.ZERO);
        placeStatsProperties.setBookmarkDeltaRetryDelay(Duration.ZERO);
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        given(deltaProcessor.consumeAndApply()).willThrow(new RuntimeException("boom"));

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(batchProcessor, times(3)).recalculateReviewCounts(any(LocalDateTime.class));
        verify(deltaProcessor, times(1)).consumeAndApply();
    }

    /**
     * <b>공용 {@code batch-*} 키의 사정거리가 좁아졌다 — 매시 두 축은 더 이상 그 키를 읽지 않는다.</b>
     * 이것을 모르고 배포하면 {@code batch-max-attempts}를 조정해 온 환경에서 매시 두 축의 재시도가
     * 조용히 기본값으로 돌아간다. 실제로 갈렸는지 확인할 수 있는 유일한 모양이 <b>공용 키만
     * 극단값으로 돌려 보는 것</b>이라, 여기서 1을 넣고 두 축이 각자의 3을 지키는지 본다.
     *
     * <p>안전망·점수 회차가 여전히 이 키를 따른다는 반대 방향은 {@code PlaceStatsFacadeTest}의
     * 몫이다 — 그쪽 계약은 분리로 달라지지 않았다.
     */
    @Test
    void 공용_batch_키는_매시_두_축을_더_이상_덮지_않는다() {
        placeStatsProperties.setBatchMaxAttempts(1);
        placeStatsProperties.setBatchRetryDelay(Duration.ZERO);
        placeStatsProperties.setReviewCountRetryDelay(Duration.ZERO);
        placeStatsProperties.setBookmarkDeltaRetryDelay(Duration.ZERO);
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        given(deltaProcessor.consumeAndApply()).willThrow(new RuntimeException("boom"));

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        verify(batchProcessor, times(placeStatsProperties.getReviewCountMaxAttempts()))
                .recalculateReviewCounts(any(LocalDateTime.class));
        verify(deltaProcessor, times(placeStatsProperties.getBookmarkDeltaMaxAttempts()))
                .consumeAndApply();
    }

    /**
     * 시도 <b>횟수</b>뿐 아니라 <b>대기</b>도 축마다 따로 붙는다. 대기 값은 실행 시간이 아니라
     * 재시도 경고 줄에 그대로 찍히므로, 로그 문구로 어느 키가 읽혔는지 갈린다.
     *
     * <p>대기를 실제로 재우지 않고 문구로 보는 이유는 그것이 <b>더 정확한 단언</b>이기 때문이다 —
     * 잠든 시간을 재면 스케줄링 지터가 섞이지만, 이 줄은 파사드가 읽은 값 자체다.
     */
    @Test
    void 재시도_대기도_축마다_제_키를_읽는다() {
        placeStatsProperties.setReviewCountRetryDelay(Duration.ZERO);
        placeStatsProperties.setBookmarkDeltaRetryDelay(Duration.ofMillis(7));
        willThrow(new RuntimeException("boom"))
                .given(batchProcessor).recalculateReviewCounts(any(LocalDateTime.class));
        given(deltaProcessor.consumeAndApply()).willThrow(new RuntimeException("boom"));

        placeStatsFacade.recalculateReviewCounts();
        placeStatsFacade.consumeBookmarkCountDeltas();

        assertThat(warnMessages())
                .anyMatch(message -> message.startsWith("인기순 리뷰 카운트 재계산 실패 - 0ms 뒤"))
                .anyMatch(message -> message.startsWith("북마크 카운트 델타 소비 실패 - 7ms 뒤"));
    }

    // === 회차 종료 로그 ===

    /**
     * <b>회차마다 시작 줄과 종료 줄이 짝을 이룬다.</b> 짝이 없는 시작 줄은 회차가 끝나지 않았다는
     * 신호이므로, 종료 줄이 빠지면 그 신호 자체가 사라진다.
     *
     * <p>성공한 회차의 종료 줄이 더하는 것은 결과가 아니라 <b>소요</b>다. 시도별 완료 줄의
     * {@code elapsed}는 성공한 시도 하나만 재므로 {@code lockAtMostFor}·회차 간격과 견줄 수 없다.
     */
    @Test
    void 리뷰_회차는_성공해도_시작_종료_한_쌍으로_소요를_남긴다() {
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class))).willReturn(10);

        placeStatsFacade.recalculateReviewCounts();

        assertThat(messagesContaining("인기순 리뷰 카운트 배치 시작")).hasSize(1);
        ILoggingEvent finished = singleEventContaining("인기순 리뷰 카운트 회차 종료");
        assertThat(finished.getFormattedMessage()).contains("결과=성공");
        assertThat(elapsedOf(finished)).isNotNegative();
    }

    /**
     * 북마크 축도 같은 짝을 남긴다. 함께 보는 이유는 <b>{@code affectedRows}의 뜻이 두 축에서
     * 정반대</b>이기 때문이다 — 리뷰 축은 문장이 걸린 행 수(= 장소 수)이고, 이쪽은 이번 회차가
     * 삼킨 <em>전표 수</em>, 곧 지난 한 시간의 토글 수다. 이 회차의 비용이 총 행 수가 아니라
     * 전표 수에 붙는다는 것이 델타 전환의 요점이라, 로그에 남는 값도 그쪽이어야 한다.
     */
    @Test
    void 북마크_회차도_성공하면_삼킨_전표_수와_함께_한_쌍을_남긴다() {
        given(deltaProcessor.consumeAndApply()).willReturn(new DeltaResult(7, 3));

        placeStatsFacade.consumeBookmarkCountDeltas();

        assertThat(messagesContaining("북마크 카운트 델타 배치 시작")).hasSize(1);
        assertThat(singleEventContaining("북마크 카운트 델타 소비 완료").getFormattedMessage())
                // 갱신된 장소 수(3)가 아니라 소비한 전표 수(7)다
                .contains("affectedRows=7");
        assertThat(singleEventContaining("북마크 카운트 델타 회차 종료").getFormattedMessage())
                .contains("결과=성공");
    }

    /**
     * <b>실패한 회차야말로 종료 줄이 필요한 자리다.</b> 시도별 완료 줄이 아예 없으므로, 이 줄이
     * 빠지면 회차가 얼마나 걸렸는지가 로그 어디에도 남지 않는다 — 재시도 대기를 늘려도 되는지,
     * {@code lockAtMostFor} 10분에 얼마나 가까운지를 판단할 근거가 사라진다.
     *
     * <p><b>레벨이 {@code info}인 것도 계약이다.</b> 원인과 스택은 {@code runWithRetry}가 이미
     * {@code error}로 냈고, 여기서 한 번 더 올리면 회차 하나가 알림을 두 번 울린다.
     *
     * <p>소요에 하한을 거는 이유는 이 줄의 존재 이유가 <b>대기와 실패한 시도까지 포함한다</b>는
     * 것이기 때문이다. 상한을 두지 않는 이유는 CI에서 얼마든지 늘어질 수 있어서다.
     */
    @Test
    void 북마크_회차는_모두_실패해도_대기를_포함한_소요를_남긴다() {
        placeStatsProperties.setBookmarkDeltaRetryDelay(RETRY_DELAY);
        given(deltaProcessor.consumeAndApply()).willThrow(new RuntimeException("boom"));

        placeStatsFacade.consumeBookmarkCountDeltas();

        ILoggingEvent finished = singleEventContaining("북마크 카운트 델타 회차 종료");
        assertThat(finished.getLevel()).isEqualTo(Level.INFO);
        assertThat(finished.getFormattedMessage()).contains("결과=실패");
        // 최대 시도 3회 = 대기 2회. 대기가 회차 소요에서 빠지면 한 자릿수 ms가 되어 여기서 걸린다
        assertThat(elapsedOf(finished)).isGreaterThanOrEqualTo(TWO_WAITS_LOWER_BOUND_MS);
    }

    /**
     * <b>두 {@code elapsed}가 재는 구간이 다르다는 것을 한 무대에서 본다.</b> 첫 시도가 실패하고
     * 두 번째가 성공한 회차에서, 시도별 완료 줄은 <em>성공한 시도 하나</em>만 재고 종료 줄은
     * 실패한 시도와 대기까지 안은 회차 전체를 잰다.
     *
     * <p>이 차이가 사라지는 회귀 — 종료 줄이 마지막 시도만 재거나 시작 시각을 시도마다 다시 잡는
     * 모양 — 가 정확히 이 단언의 표적이다. 성공한 회차만 보면 두 값이 거의 같아 구분되지 않는다.
     */
    @Test
    void 회차_소요는_실패한_시도와_재시도_대기를_품는다() {
        placeStatsProperties.setReviewCountRetryDelay(RETRY_DELAY);
        given(batchProcessor.recalculateReviewCounts(any(LocalDateTime.class)))
                .willThrow(new RuntimeException("boom"))
                .willReturn(10);

        placeStatsFacade.recalculateReviewCounts();

        ILoggingEvent attempt = singleEventContaining("인기순 리뷰 카운트 재계산 완료");
        assertThat(attempt.getFormattedMessage()).contains("시도=2/3");
        ILoggingEvent finished = singleEventContaining("인기순 리뷰 카운트 회차 종료");
        assertThat(finished.getFormattedMessage()).contains("결과=성공");

        assertThat(elapsedOf(finished))
                .isGreaterThanOrEqualTo(ONE_WAIT_LOWER_BOUND_MS)
                .isGreaterThan(elapsedOf(attempt));
    }

    // === 헬퍼 ===

    /** 기본값이 없으면 여기서 {@code IllegalArgumentException}으로 터진다 (= 기동 실패와 같은 지점) */
    private String resolvedCron(String methodName) throws Exception {
        return new StandardEnvironment().resolveRequiredPlaceholders(rawCron(methodName));
    }

    /** 해석 <b>전</b>의 문자열 — 어느 프로퍼티 키를 읽는지는 여기에만 남아 있다. */
    private String rawCron(String methodName) throws Exception {
        return PlaceStatsFacade.class.getMethod(methodName).getAnnotation(Scheduled.class).cron();
    }

    private String lockName(String methodName) throws Exception {
        return PlaceStatsFacade.class.getMethod(methodName)
                .getAnnotation(SchedulerLock.class).name();
    }

    private List<String> messagesContaining(String needle) {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(needle))
                .toList();
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 종료 줄이 <b>회차당 하나</b>라는 것도 함께 본다 — 두 줄이면 짝 맞추기가 무너진다. */
    private ILoggingEvent singleEventContaining(String needle) {
        List<ILoggingEvent> matched = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(needle))
                .toList();
        assertThat(matched).as("'%s'을(를) 담은 로그", needle).hasSize(1);
        return matched.get(0);
    }

    private long elapsedOf(ILoggingEvent event) {
        Matcher matcher = ELAPSED.matcher(event.getFormattedMessage());
        assertThat(matcher.find()).as("elapsed=…ms 가 빠진 로그: %s", event.getFormattedMessage())
                .isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
