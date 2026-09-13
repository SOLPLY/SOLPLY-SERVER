package org.sopt.solply_server.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 실행기 둘 — 집계 회차용 풀과 스냅샷 폴 전용 스레드.
 *
 * <p><b>폴이 전용 스레드를 갖는 이유.</b> 목록 스냅샷 폴의 간격은 커서가 오르지 않는 변경(어드민
 * 표시값 수정)이 다른 인스턴스에 닿는 상한이다. 집계 회차와 같은 실행기에 있으면 몇 분짜리 회차
 * 뒤에 줄을 서게 되어, 그 상한이 폴 간격이 아니라 앞 회차의 소요가 된다.
 *
 * <p><b>배치 풀이 4인 이유.</b> cron 작업은 일곱이고 서로 5분 이상 엇갈려 있다 — 델타(:00 :15 :30
 * :45) · 리뷰(:05 :20 :35 :50) · 점수(매시 :10) · 안전망(01:25) · 인증 정리(04:40) · 장소
 * 임베딩(03:00) · 코스 임베딩(04:00). 스레드가 하나 더 소모되는 것은 회차가 자기 간격을 넘겨
 * 길어질 때뿐이므로 현실적인 동시 겹침은 2~3개다.
 *
 * <p><b>풀 크기를 프로퍼티로 빼지 않는다.</b> {@code application.yml}은 추적되지 않고 CI가 시크릿
 * 본문으로 덮어쓰므로, 설정으로 두면 배포마다 사람이 챙겨야 하는 값이 하나 늘어난다.
 *
 * <p><b>풀이 바꾸지 않는 것.</b> 같은 {@code @Scheduled} 메서드는 한 JVM 안에서 자기 자신과 겹치지
 * 않는다 — 스프링은 메서드가 끝난 뒤에 다음 cron 시각을 계산하므로, 회차가 길어져 자기 다음 발화를
 * 지나치면 <b>그 발화는 건너뛰어진다</b>. 인스턴스 간 겹침은 ShedLock의 몫이고, 임대 만료 뒤에
 * 남는 겹침 창도 그대로다 ({@code PlaceStatsFacade}).
 *
 * <p><b>풀이 새로 만드는 것.</b> 한 JVM에서 <b>서로 다른</b> 회차가 동시에 돌 수 있다. 점수 문장은
 * {@code place_stats} 전 행을 갱신하므로 다른 회차와 DB에서 잠금을 다툴 수 있고, 회차들을 5분씩
 * 엇갈려 둔 남은 역할은 스레드 대기 회피가 아니라 <b>이 DB 경합 회피</b>다.
 *
 * <p><b>⚠️ 두 빈의 선언 타입을 {@link TaskScheduler}에서 넓히지 말 것.</b>
 * {@code ThreadPoolTaskScheduler}는 {@code Executor}이기도 해서, 반환 타입을 그 구현체로 적으면
 * 부트의 {@code applicationTaskExecutor}가
 * {@code @ConditionalOnMissingBean(Executor.class)}에 걸려 <b>사라진다</b>. 그 빈을 이름으로 주입받는
 * 자리({@code PlaceListRequestOrchestrator}의 {@code resumeExecutor})가 있어 기동이 통째로 깨지고,
 * 스케줄링과 무관한 테스트 컨텍스트까지 함께 죽는다. 선언 타입이 {@code TaskScheduler}면 조건이
 * 그 빈을 {@code Executor}로 보지 않아 둘이 공존한다 — 실제 객체가 무엇인지와 별개로
 * <b>선언 타입이 계약이다.</b>
 */
@Configuration
public class SchedulingConfig {

    /** 동시에 도는 회차 2~3개에 여유 한 칸 — 근거는 클래스 javadoc */
    private static final int BATCH_POOL_SIZE = 4;

    /**
     * 배치 회차용 실행기.
     *
     * <p><b>빈 이름이 정확히 {@code taskScheduler}여야 한다.</b> {@code TaskScheduler} 빈이 둘일 때
     * {@code ScheduledAnnotationBeanPostProcessor}는 {@code scheduler} 속성이 없는
     * {@code @Scheduled}를 이 이름의 빈으로 보낸다. 이름이 달라지면 그 해석이 "타입으로 유일한
     * 빈 찾기"에 걸려 기동이 깨진다.
     *
     * <p>이 빈이 있으면 부트의 {@code TaskSchedulingAutoConfiguration} 기본 {@code taskScheduler}는
     * 물러난다({@code @ConditionalOnMissingBean TaskScheduler}).
     *
     * <p>반환 타입이 {@code TaskScheduler}인 이유는 클래스 javadoc의 마지막 경고에 있다.
     */
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(BATCH_POOL_SIZE);
        scheduler.setThreadNamePrefix("batch-sched-");
        return scheduler;
    }

    /**
     * 스냅샷 폴 전용 실행기 — 쓰는 쪽은 {@code SnapshotLoadCoordinator#pollRebuild} 하나다.
     *
     * <p>풀 크기가 1인 것이 계약이다. 폴은 자기 자신과 겹치지 않아야 하고({@code fixedDelay}가
     * 그것을 보장한다) 여기에 다른 작업을 얹으면 폴이 다시 남의 소요를 기다린다.
     */
    @Bean(name = "snapshotPollScheduler")
    public TaskScheduler snapshotPollScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("snapshot-poll-");
        return scheduler;
    }
}
