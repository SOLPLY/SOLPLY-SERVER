package org.sopt.solply_server.domain.place.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 목록 조회 경로의 런타임 스위치.
 *
 * <p><b>기본값이 저장소에 남는 유일한 선언이다.</b> {@code application.yml}은 .gitignore의
 * {@code *.yml}에 걸려 커밋되지 않으므로(시크릿이 들어 있다) yml에 적은 값은 각 환경에만 존재한다.
 * {@code PlaceStatsProperties}와 같은 사정이다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "solply.place-list")
public class PlaceListProperties {

    /**
     * 장소 골격 스냅샷 캐시({@code PlaceSkeletonSnapshot}) 사용 여부.
     *
     * <p><b>A/B를 같은 빌드에서 돌리기 위한 스위치다.</b> 이 값이 무엇이든 응답 body와 커서 토큰은
     * 같아야 하며, 그것이 이 캐시의 계약이다. 다르면 캐시가 아니라 버그다.
     *
     * <p><b>{@code false}면 스냅샷을 <em>짓지도</em> 않는다.</b> 읽지 않는 값을 매시 짓는 것은
     * 순전한 낭비이기도 하지만, 더 중요한 이유는 측정이다 — off 라운드가 비교의 기준선인데
     * 거기에 빌드 시점의 CPU·풀 점유가 섞이면 두 모드의 차이가 캐시 효과인지 배치 잡음인지
     * 갈라낼 수 없다. 끄면 조회 경로는 스냅샷을 아예 보지 않고 기존 쿼리로만 간다.
     *
     * <p>런타임에 {@code true}로 되돌리면 다음 카운트 배치(≤1h)에서 스냅샷이 채워진다.
     * 그 전까지는 전량 미스라 응답은 여전히 옳고 성능만 기존과 같다.
     */
    private boolean skeletonCacheEnabled = true;
}
