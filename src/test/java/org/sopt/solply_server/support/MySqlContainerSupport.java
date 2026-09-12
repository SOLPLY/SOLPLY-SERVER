package org.sopt.solply_server.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Testcontainers MySQL + Flyway 부트스트랩 공통 베이스.
 *
 * <p>ddl-auto는 의도적으로 하위 클래스가 정하도록 남긴다. 스키마 정합을 검증하는 IT는 validate로
 * 돌아야 하는데, 이 차이가 사라지면 엔티티↔마이그레이션 불일치를 아무도 못 잡는다
 * (실제로 컬럼 타입 불일치가 빌드를 통과해 부팅 시점에 터진 적이 있다).
 *
 * <p><b>싱글턴 컨테이너 패턴 — {@code @Testcontainers}/{@code @Container}를 쓰지 않는다.</b>
 * 그 어노테이션 조합은 static 필드라도 클래스마다 {@code beforeAll}에서 start /
 * {@code afterAll}에서 stop을 돌린다. 베이스로 뽑아도 같은 static 객체가 IT 클래스 수만큼
 * 죽었다 살아나서(실측: {@code Creating container for image: mysql:8.0}이 4회) 컨테이너 1개 +
 * Flyway 1회라는 이득을 못 얻고, JUnit 병렬 실행을 켜면 여러 클래스가 같은 인스턴스를 동시에
 * start/stop해 깨진다. 그래서 static 초기화 블록에서 한 번만 start하고 stop은 하지 않는다 —
 * 정리는 Ryuk 사이드카가 JVM 종료 시 맡는다.
 *
 * <p>컨텍스트 캐시 부작용은 없다. 하위 클래스마다 {@code @DynamicPropertySource} 메서드 집합이
 * 달라 {@code MergedContextConfiguration}이 분리되므로, 컨테이너를 공유해도 컨텍스트는 섞이지 않는다.
 *
 * <p><b>메서드 이름 주의:</b> {@code @DynamicPropertySource}는 static 메서드라 하위 클래스가 같은
 * 이름을 쓰면 이 메서드를 <em>숨겨</em> 데이터소스 설정이 통째로 사라진다. 하위는 {@code ddlAuto}처럼
 * 반드시 다른 이름을 쓸 것.
 */
public abstract class MySqlContainerSupport {

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // 컨텍스트마다 풀이 하나씩 살아 남는다 — 기본값 10이면 IT 클래스가 늘 때
        // 컨테이너의 max_connections(151)를 넘어 엉뚱한 IT가 "Too many connections"로 죽는다
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.flyway.placeholders.s3_env", () -> "test");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("decorator.datasource.enabled", () -> "false");
        quietSnapshotRefresh(registry);
    }

    /**
     * 목록 스냅샷의 <b>발행 폴을 IT에서 사실상 멈추고, 채택 폴은 짧게 남긴다.</b>
     *
     * <p><b>발행 폴을 멈추는 이유.</b> 기본 5초 {@code fixedDelay}로 돌면 발행자가 테스트의 단언
     * 사이에 끼어들어 발행물을 하나 더 만들고 밀린 요청을 닫는다 — 그 IT가 <b>무엇을 보고 무엇을
     * 단언했는지</b>가 시점에 따라 달라진다. 발행은 테스트가 명시적으로 부르는 것만 돌아야 한다.
     *
     * <p><b>채택 폴은 왜 크게 잡지 못하나.</b> {@code SnapshotScheduler#restoreOnStartup}이
     * 최초 발행을 지은 <em>직후</em> 설치 전에 <b>한 폴 간격만큼 잠든다.</b> 그래서 이 값을 한
     * 시간으로 잡으면 발행물이 없는 상태로 뜨는 컨텍스트가 기동에서 한 시간 멈춘다(실측:
     * {@code SnapshotPublicationIT}가 10분 넘게 진행되지 않았다). 기동 한 번의 대기가 이 값이므로
     * 짧게 둔다. 다만 너무 짧으면 캐시에 살아 있는 컨텍스트 스무 개가 저마다 폴을 돌려 커넥션
     * 경합이 생기므로, 기동 대기와 배경 부하가 함께 견딜 만한 값으로 잡는다.
     *
     * <p>채택 폴이 배경에서 도는 것은 해롭지 않다 — 포인터가 그대로면 payload를 읽지도 않고,
     * 움직였으면 설치하는 것이 맞는 동작이다. 위험한 쪽은 새 발행물을 <em>만드는</em> 발행 폴이다.
     *
     * <p><b>폴 간격을 되돌리려고 하위 클래스에서 같은 키를 다시 등록하지 말 것.</b>
     * {@code @DynamicPropertySource}는 하위 것이 먼저, 상위 것이 나중에 불려 <b>상위가 하위를
     * 덮는다.</b> 폴을 실제로 돌려야 하는 검증은 스케줄러를 기다리는 대신 발행자·설치자를 직접
     * 부르거나, 이 베이스를 쓰지 않고 컨텍스트를 따로 세운다.
     */
    protected static void quietSnapshotRefresh(DynamicPropertyRegistry registry) {
        registry.add("solply.place-list-snapshot.publish-poll-interval-ms", () -> "3600000");
        registry.add("solply.place-list-snapshot.adopt-poll-interval-ms", () -> "1500");
    }
}
