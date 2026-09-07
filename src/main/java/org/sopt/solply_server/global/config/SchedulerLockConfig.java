package org.sopt.solply_server.global.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock — {@code @Scheduled} 배치의 다중 인스턴스 중복 실행 방지.
 *
 * <p>락 저장소가 Redis가 아니라 MySQL인 이유: 락이 보호하는 작업(place_stats 배치)의 유일한
 * 의존성이 MySQL이다. 락을 같은 DB에 두면 "DB가 살아 있으면 락도 배치도 동작"으로 실패
 * 도메인이 일치한다. Redis에 두면 Redis 장애가 배치를 멈추는 새 실패 경로가 생긴다.
 *
 * <p>{@code usingDbTime()} — 락 만료 판정을 DB 서버 시각으로 한다. 인스턴스 간 시계가
 * 어긋나도(컨테이너 환경에서 드물지 않다) 만료 판정이 갈리지 않는다.
 *
 * <p>{@code defaultLockAtMostFor}는 안전망 기본값일 뿐이고, 실제 값은 각
 * {@code @SchedulerLock}이 명시한다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
