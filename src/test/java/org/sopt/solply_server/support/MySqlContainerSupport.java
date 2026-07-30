package org.sopt.solply_server.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers MySQL + Flyway 부트스트랩 공통 베이스.
 *
 * <p>ddl-auto는 의도적으로 하위 클래스가 정하도록 남긴다. 스키마 정합을 검증하는 IT는 validate로
 * 돌아야 하는데, 이 차이가 사라지면 엔티티↔마이그레이션 불일치를 아무도 못 잡는다
 * (실제로 컬럼 타입 불일치가 빌드를 통과해 부팅 시점에 터진 적이 있다).
 *
 * <p><b>메서드 이름 주의:</b> {@code @DynamicPropertySource}는 static 메서드라 하위 클래스가 같은
 * 이름을 쓰면 이 메서드를 <em>숨겨</em> 데이터소스 설정이 통째로 사라진다. 하위는 {@code ddlAuto}처럼
 * 반드시 다른 이름을 쓸 것.
 */
@Testcontainers
public abstract class MySqlContainerSupport {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.flyway.placeholders.s3_env", () -> "test");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("decorator.datasource.enabled", () -> "false");
    }
}
