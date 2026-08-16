package org.sopt.solply_server.domain.place.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.domain.place.config.PlaceListProperties;
import org.sopt.solply_server.domain.place.config.PlaceListProperties.SortSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 시 정렬 스냅샷을 <b>동기로</b> 짓는다. 실패하면 상한을 둔 백오프로 다시 시도한다.
 *
 * <p><b>{@code ApplicationRunner}인 이유는 {@code PlaceSkeletonWarmup}과 같다</b> — 부트가 러너를
 * 전부 돌린 뒤에야 {@code ApplicationReadyEvent}와 {@code ACCEPTING_TRAFFIC}이 올라가므로, 이
 * 메서드는 이미 "트래픽을 받기 전" 구간 안이다. readiness probe를 따로 배선해도 더 일찍 막아 주는
 * 것이 없다.
 *
 * <p><b>이 훅이 벤치의 팔 교대를 성립시킨다.</b> 스냅샷은 {@code sort-source=memory}일 때만 지어
 * 지는데, 그 값은 재기동으로 들어온다({@code SPRING_APPLICATION_JSON}). 여기서 짓지 않으면 새로 뜬
 * 인스턴스는 다음 카운트 배치(≤1h)까지 스냅샷이 없어 <b>DB 경로로 되돌아간 채 측정</b>된다 —
 * 즉 B를 잰다고 하고 A를 재게 된다.
 *
 * <p><b>최종 실패해도 기동을 막지 않는다.</b> 스냅샷이 없으면 조회 경로가 DB로 되돌아가므로 응답은
 * 옳다 — 측정만 무의미해진다. 그 상황을 아래 {@code log.error}가 남기고, 조회 경로도 첫 되돌림을
 * 한 번 경고한다 ({@code PlaceService#listPlaces}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceSortWarmup implements ApplicationRunner {

    private final PlaceSortSnapshotLoader loader;
    private final PlaceListProperties properties;

    /**
     * 시도별 대기(ms). 1s → 2s → 4s → 8s → 10s로 늘리다 상한에서 멈춘다.
     * 상한을 두는 근거는 골격 워밍업과 같다 — DB가 통째로 죽은 상황이면 더 기다려도 뜨지 않고,
     * 그 사이 기동이 멈춰 있는 것 자체가 배포 롤백을 늦춘다.
     */
    private static final long[] BACKOFF_MILLIS = {1_000L, 2_000L, 4_000L, 8_000L, 10_000L};

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getSortSource() != SortSource.MEMORY) {
            log.info("장소 정렬 스냅샷 기동 빌드 생략 - sort-source={}", properties.getSortSource());
            return;
        }
        for (int attempt = 0; attempt <= BACKOFF_MILLIS.length; attempt++) {
            try {
                loader.rebuild();
                return;
            } catch (Exception e) {
                if (attempt == BACKOFF_MILLIS.length) {
                    log.error("장소 정렬 스냅샷 기동 빌드 최종 실패 - 시도={}회. "
                            + "스냅샷 없이 계속한다(조회는 DB 경로로 되돌아간다)", attempt + 1, e);
                    return;
                }
                long waitMillis = BACKOFF_MILLIS[attempt];
                log.warn("장소 정렬 스냅샷 기동 빌드 실패 - 시도={}회, {}ms 뒤 재시도",
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
            log.warn("장소 정렬 스냅샷 기동 빌드 재시도 중단 - 인터럽트");
            return false;
        }
    }
}
