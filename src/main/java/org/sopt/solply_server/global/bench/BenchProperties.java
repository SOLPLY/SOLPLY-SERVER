package org.sopt.solply_server.global.bench;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 벤치 통로의 스위치. <b>기본값은 꺼짐이고, 운영 설정에는 이 키가 없다.</b>
 *
 * <p>{@code /bench/**}는 캐시를 비우고 전량 재빌드를 부르는 <b>무인증</b> 통로라, 켜는 조건을
 * 한 겹으로 두지 않는다 — {@code @Profile("bench")} <b>그리고</b> 이 프로퍼티 <b>그리고</b>
 * 보안 설정의 허용이 모두 맞아야 선다. 프로파일이 실수로 상속돼도
 * ({@code SPRING_PROFILES_ACTIVE}, 테스트 설정, 컨테이너 환경변수) 이 값이 없으면 빈이 서지 않고,
 * 서더라도 보안이 막는다.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "solply.bench")
public class BenchProperties {

    /** 기본 {@code false}. 비교 이미지에서만 켠다. */
    private boolean enabled = false;
}
