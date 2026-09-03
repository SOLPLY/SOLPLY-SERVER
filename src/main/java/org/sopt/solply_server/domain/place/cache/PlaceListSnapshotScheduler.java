package org.sopt.solply_server.domain.place.cache;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link PlaceListSnapshotLoader#rebuild()}를 부르는 트리거 셋 중 <b>둘</b> — 기동 한 번과 10분
 * 주기다. 나머지 하나는 어드민 커밋 훅({@link PlaceListSnapshotRefresher})이다.
 *
 * <p><b>이 타이머가 맡은 것은 통계의 신선도다.</b> 카운트·점수는 배치가 RDB에 채우고 그 값이
 * 화면에 닿는 것은 다음 회차이므로, <b>카운트·점수의 반영 상한이 곧 이 주기(10분)</b>다. 반면
 * 어드민이 바꾼 콘텐츠(장소의 생성·수정·삭제·재활성)는 이 타이머를 기다리지 않는다 — 그 쓰기
 * 트랜잭션이 커밋 직후 사진을 다시 찍는다(수백 ms). 그래서 SLA가 둘로 갈린다:
 * <b>어드민 변경은 커밋 직후, 카운트·점수는 최대 10분.</b> 비활성화한 장소가 목록에 남는 창은
 * 정상 경로에는 없고 <b>어드민 훅의 재생성이 실패했을 때만</b> 다음 주기 발화까지 열린다.
 *
 * <p><b>기동 빌드는 동기이고 실패하면 기동을 막는다.</b> 싱글턴 빈 초기화는 서블릿 컨테이너가
 * 포트를 열기 <em>전</em>에 끝나므로, 여기서 성공하면 트래픽이 빈 스냅샷을 보는 창이 아예 없다.
 * 반대로 여기서 예외를 삼키면 스냅샷 없는 인스턴스가 트래픽을 받게 되는데, 목록 응답이 곧 이
 * 스냅샷이라 그것은 느려지는 것이 아니라 <b>목록이 통째로 비는 오답</b>이다. 그래서 예외를 그대로
 * 흘려 컨텍스트 기동을 실패시킨다 — 뜨지 않는 인스턴스는 로드밸런서가 알아서 뺀다.
 *
 * <p><b>주기 발화는 반대로 예외를 삼킨다.</b> 이미 서빙 중인 인스턴스가 한 회차 실패로 죽으면
 * 안 되고, 실패해도 <b>직전 회차의 사진이 그대로 남아</b> 응답은 여전히 정합적이다. 낡음의 상한이
 * 10분에서 "다음 성공까지"로 늘어날 뿐이며, 그 사실은 아래 {@code log.error}가 남긴다.
 *
 * <p><b>ShedLock을 걸지 않는다.</b> 이 회차가 고치는 것은 공유 DB가 아니라 <b>인스턴스 자신의 힙</b>
 * 이라, 인스턴스마다 각자 돌아야 한다. 리더 하나만 돌면 나머지는 영원히 기동 시점의 사진을 서빙한다.
 *
 * <p>{@code @Scheduled} 기본 실행기는 단일 스레드다. 이 회차는 10분에 한 번 수백 ms를 쓰므로
 * 다른 배치를 눈에 띄게 밀지 않지만, <b>주기를 크게 당길 때는 그 경합부터 확인할 것</b>
 * ({@code PlaceStatsFacade}가 정각을 피해 30분에 도는 것과 같은 이유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceListSnapshotScheduler {

    private static final long INTERVAL_MINUTES = 10L;

    private final PlaceListSnapshotLoader loader;

    /**
     * 기동 빌드. <b>예외를 잡지 않는다</b> — 실패는 곧 컨텍스트 기동 실패다(클래스 javadoc 참조).
     */
    @PostConstruct
    public void buildOnStartup() {
        loader.rebuild();
    }

    /**
     * 주기 빌드. 첫 발화가 기동 10분 뒤인 것은 {@link #buildOnStartup()}이
     * 방금 찍은 사진을 곧바로 다시 찍지 않기 위해서다.
     *
     * <p>{@code fixedDelay}라 <b>직전 회차가 끝난 시점부터</b> 간격을 센다. 회차가 길어져도 다음
     * 회차가 겹쳐 들어오지 않는다.
     */
    @Scheduled(initialDelay = INTERVAL_MINUTES, fixedDelay = INTERVAL_MINUTES,
            timeUnit = TimeUnit.MINUTES)
    public void rebuildPeriodically() {
        try {
            loader.rebuild();
        } catch (Exception e) {
            log.error("장소 목록 스냅샷 주기 갱신 실패 - 직전 회차 사진을 유지한다"
                    + "(다음 성공까지 낡은 값이 나간다)", e);
        }
    }
}
