package org.sopt.solply_server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 실행기 분리의 계약을 스프링 컨텍스트에서 문다 — <b>DB도 부트 자동설정도 없이</b>
 * {@link SchedulingConfig}와 탐침 빈만 올린다.
 *
 * <p><b>배치 풀을 일부러 굶기는 것이 이 테스트의 방법이다.</b> 탐침 넷이 각자 래치를 붙잡고 앉아
 * 배치 스레드 넷을 전부 점유한 상태를 만든 뒤, 그 동안 폴이 계속 발화하는지 본다. 실행기가 하나로
 * 합쳐지면 폴은 이 상태에서 한 번도 돌지 못한다.
 *
 * <p><b>{@code Thread.sleep}으로 시간을 재지 않는다.</b> 모든 단언이 래치와 타임아웃으로 갈리므로
 * 느린 CI에서 흔들리지 않는다 — 실패는 "타임아웃 안에 못 일어났다"로만 나온다.
 *
 * <p>탐침 넷이 동시에 자기 본문 안에 들어가 있다는 사실이 <b>서로 다른 회차가 한 JVM에서 병렬로
 * 돈다</b>는 계약의 증거이기도 하다 — 풀 크기가 1이면 {@code occupied} 래치가 영영 0이 되지 않는다.
 */
@SpringJUnitConfig(classes = {SchedulingConfig.class, SchedulingConfigTest.ProbeConfig.class})
class SchedulingConfigTest {

    /** 래치 대기 상한. 넉넉히 잡는다 — 이 값이 크다고 통과가 느려지지는 않는다 */
    private static final long AWAIT_TIMEOUT_SECONDS = 10;

    @Autowired private Probes probes;

    /**
     * 빈을 <b>이름으로</b> 꺼낸다. 두 빈의 선언 타입은 {@code TaskScheduler}이고 그것이 계약이라
     * ({@link SchedulingConfig} 마지막 경고) 구현체 타입으로 주입받으면 안 된다 — 선언 타입을
     * 넓히라는 압력이 테스트에서 올라오면 그 계약이 조용히 깨진다.
     */
    @Autowired private ConfigurableApplicationContext context;

    /** 붙잡아 둔 스레드를 반드시 풀어 준다 — 안 풀면 컨텍스트 종료가 그만큼 늦어진다 */
    @AfterEach
    void releaseBatchThreads() {
        probes.release.countDown();
    }

    @Test
    @DisplayName("배치 스레드를 모두 붙잡아 둔 상태에서도 스냅샷 폴은 전용 스레드에서 계속 발화한다")
    void 배치_풀이_굶어도_폴은_자기_스레드에서_돈다() throws Exception {
        assertThat(probes.occupied.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("배치 탐침 넷이 모두 자기 본문에 들어가 스레드를 점유했다")
                .isTrue();

        assertThat(probes.polled.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("배치 풀이 꽉 찬 동안에도 폴이 두 번 이상 발화한다"
                        + " — 실행기를 합치면 여기서 타임아웃이 난다")
                .isTrue();
        assertThat(probes.pollThreadName.get())
                .as("폴이 도는 스레드")
                .startsWith("snapshot-poll-");
    }

    @Test
    @DisplayName("서로 다른 기본 스케줄 메서드가 배치 풀에서 동시에 돈다")
    void 서로_다른_회차가_같은_순간에_함께_돈다() throws Exception {
        assertThat(probes.occupied.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("넷이 동시에 자기 본문 안에 있다 = 풀 크기가 1보다 크다")
                .isTrue();

        assertThat(probes.batchThreadNames)
                .as("점유한 스레드가 서로 다른 넷이다")
                .hasSize(4);
        assertThat(probes.batchThreadNames)
                .allSatisfy(name -> assertThat(name).startsWith("batch-sched-"));
    }

    @Test
    @DisplayName("두 실행기의 풀 크기는 각각 4와 1이다")
    void 두_실행기의_풀_크기가_계약대로다() {
        assertThat(scheduler("taskScheduler").getScheduledThreadPoolExecutor().getCorePoolSize())
                .as("배치 풀")
                .isEqualTo(4);
        assertThat(scheduler("snapshotPollScheduler").getScheduledThreadPoolExecutor()
                .getCorePoolSize())
                .as("폴 전용 실행기")
                .isEqualTo(1);
    }

    /**
     * <b>두 빈의 선언 타입은 {@code Executor}가 아니다.</b> 넓히는 순간 부트의
     * {@code applicationTaskExecutor}가 {@code @ConditionalOnMissingBean(Executor.class)}에 걸려
     * 사라지고, 그 빈을 이름으로 주입받는 자리가 있어 애플리케이션 컨텍스트가 통째로 깨진다 —
     * 스케줄링과 아무 상관 없는 테스트들이 함께 죽는 모양으로 드러난다.
     *
     * <p>여기서 <b>빈 정의의 타입</b>을 묻는 이유는 그 조건이 보는 것이 실제 객체가 아니라 정의이기
     * 때문이다. 실제 객체는 {@code ThreadPoolTaskScheduler}이므로 객체를 물으면 언제나 통과한다.
     */
    @Test
    @DisplayName("스케줄러 빈은 Executor 타입으로 노출되지 않는다 — 부트의 applicationTaskExecutor를 밀어내지 않으려고")
    void 스케줄러_빈은_Executor로_노출되지_않는다() {
        for (String name : new String[] {"taskScheduler", "snapshotPollScheduler"}) {
            Class<?> declared = context.getBeanFactory()
                    .getBeanDefinition(name)
                    .getResolvableType()
                    .resolve();
            assertThat(declared)
                    .as("%s의 빈 정의 타입", name)
                    .isNotNull()
                    .isAssignableTo(TaskScheduler.class)
                    .matches(type -> !Executor.class.isAssignableFrom(type),
                            "Executor가 아니어야 한다");
        }
    }

    private ThreadPoolTaskScheduler scheduler(String beanName) {
        return context.getBean(beanName, ThreadPoolTaskScheduler.class);
    }

    /**
     * 탐침만 올리는 설정. {@code @EnableScheduling}이 여기 붙는 이유는 <b>테스트 클래스가 빈이
     * 아니라서</b> 거기 붙인 어노테이션은 처리되지 않기 때문이다.
     */
    @Configuration
    @EnableScheduling
    static class ProbeConfig {

        @Bean
        Probes probes() {
            return new Probes();
        }
    }

    /**
     * 스케줄 탐침 다섯. <b>넷은 배치 풀의 기본 경로</b>({@code scheduler} 속성 없음)이고
     * <b>하나는 폴 전용 실행기</b>를 명시한다 — 그 비대칭이 이 테스트가 보려는 것 전부다.
     *
     * <p>배치 탐침이 넷인 것은 풀 크기와 같은 수다. 넷이 모두 들어가야 {@code occupied}가 0이 되므로,
     * 풀이 셋 이하로 줄면 그 래치가 열리지 않아 즉시 드러난다.
     */
    static class Probes {

        /** 배치 탐침 넷이 모두 자기 본문에 들어왔는가 */
        final CountDownLatch occupied = new CountDownLatch(4);

        /** 폴이 두 번 이상 발화했는가 — 한 번은 "때마침 먼저 떴다"와 구분되지 않는다 */
        final CountDownLatch polled = new CountDownLatch(2);

        /** 붙잡아 둔 배치 스레드를 풀어 주는 신호 */
        final CountDownLatch release = new CountDownLatch(1);

        final Set<String> batchThreadNames = ConcurrentHashMap.newKeySet();
        final AtomicReference<String> pollThreadName = new AtomicReference<>();

        @Scheduled(fixedDelay = 50)
        void batchProbeOne() throws InterruptedException {
            occupyBatchThread();
        }

        @Scheduled(fixedDelay = 50)
        void batchProbeTwo() throws InterruptedException {
            occupyBatchThread();
        }

        @Scheduled(fixedDelay = 50)
        void batchProbeThree() throws InterruptedException {
            occupyBatchThread();
        }

        @Scheduled(fixedDelay = 50)
        void batchProbeFour() throws InterruptedException {
            occupyBatchThread();
        }

        /** 폴 전용 실행기에 올라가는 유일한 탐침 — 운영의 {@code pollRebuild}와 같은 배선이다 */
        @Scheduled(fixedDelay = 50, scheduler = "snapshotPollScheduler")
        void pollProbe() {
            pollThreadName.set(Thread.currentThread().getName());
            polled.countDown();
        }

        /**
         * 스레드 이름을 남기고 {@code release}까지 자리를 지킨다.
         *
         * <p>풀어 준 뒤의 발화는 곧바로 빠져나간다({@code release}가 이미 0이다) — 그래서 뒷정리가
         * 스레드를 다시 붙잡지 않는다.
         */
        private void occupyBatchThread() throws InterruptedException {
            batchThreadNames.add(Thread.currentThread().getName());
            occupied.countDown();
            release.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
