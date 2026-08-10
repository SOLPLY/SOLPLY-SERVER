package org.sopt.solply_server.domain.place.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.SkeletonSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 시 스냅샷을 <b>동기로</b> 짓는다. 실패하면 상한을 둔 백오프로 다시 시도한다.
 *
 * <p><b>{@code ApplicationRunner}인 이유 — readiness probe를 따로 배선하지 않는다.</b>
 * 스프링 부트는 {@code ApplicationRunner}를 전부 돌린 <em>뒤에</em>
 * {@code ApplicationReadyEvent}를 발행하고, {@code ReadinessState.ACCEPTING_TRAFFIC}도 그때
 * 함께 올라간다. 즉 이 메서드는 이미 "트래픽을 받기 전" 구간 안에 있으며, 여기에 probe 상태를
 * 하나 더 얹어도 <b>더 일찍 막아 주는 것이 없다</b>. 현재 배포(docker-compose + nginx)에 앱
 * 컨테이너의 readiness를 소비하는 주체도 없다 — 아무도 읽지 않는 신호를 만드는 셈이다.
 * 오케스트레이터가 probe로 라우팅을 가르는 환경으로 옮겨가면 그때 다시 볼 것.
 *
 * <p><b>최종 실패해도 기동을 막지 않는다.</b> 스냅샷이 비면 조회 경로는 전량 미스로 떨어져
 * 캐시 도입 <em>이전과 같은</em> 쿼리를 낸다 — 느려질 뿐 응답은 옳다. 여기서 예외를 흘리면
 * 그 대가로 서비스 전체가 뜨지 않는다. 다음 카운트 배치(≤1h)가 스스로 메운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceSkeletonWarmup implements ApplicationRunner {

    private final PlaceSkeletonLoader loader;
    private final PlaceListProperties properties;

    /**
     * 시도별 대기(ms). 1s → 2s → 4s → 8s → 10s로 늘리다 상한에서 멈춘다.
     * 배열 길이 + 1이 곧 최대 시도 횟수이고, 전부 실패하면 최대 25초를 쓴다.
     * <b>상한을 두는 이유:</b> DB가 통째로 죽은 상황이면 여기서 더 기다려도 뜨지 않고,
     * 그 사이 기동이 멈춰 있는 것 자체가 배포 롤백을 늦춘다.
     */
    private static final long[] BACKOFF_MILLIS = {1_000L, 2_000L, 4_000L, 8_000L, 10_000L};

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getSkeletonSource() != SkeletonSource.SNAPSHOT) {
            log.info("장소 골격 스냅샷 기동 빌드 생략 - skeleton-source={}",
                    properties.getSkeletonSource());
            return;
        }
        for (int attempt = 0; attempt <= BACKOFF_MILLIS.length; attempt++) {
            try {
                loader.rebuild();
                return;
            } catch (Exception e) {
                if (attempt == BACKOFF_MILLIS.length) {
                    log.error("장소 골격 스냅샷 기동 빌드 최종 실패 - 시도={}회. "
                            + "빈 스냅샷으로 계속한다(전량 미스 = 캐시 도입 이전 경로)",
                            attempt + 1, e);
                    return;
                }
                long waitMillis = BACKOFF_MILLIS[attempt];
                log.warn("장소 골격 스냅샷 기동 빌드 실패 - 시도={}회, {}ms 뒤 재시도",
                        attempt + 1, waitMillis, e);
                if (!sleep(waitMillis)) {
                    return;
                }
            }
        }
    }

    /** 인터럽트는 종료 신호다 — 삼키지 않고 플래그를 되살린 뒤 재시도를 포기한다. */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("장소 골격 스냅샷 기동 빌드 재시도 중단 - 인터럽트");
            return false;
        }
    }
}
