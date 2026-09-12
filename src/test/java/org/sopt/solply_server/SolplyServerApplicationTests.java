package org.sopt.solply_server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.global.cache.CacheService;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 기동 스모크 — 그리고 <b>Redis 없이도 목록 스냅샷 경로가 전부 도는가</b>(설계 §11-34).
 *
 * <p>{@code test} 프로파일은 Redis 자동설정과 {@code RedisConfig}를 모두 빼므로 이 컨텍스트에는
 * 연결 팩토리가 아예 없다. 그 상태에서 기동 복원(최초 발행 + 설치)이 끝까지 도는 것이 여기서
 * 확인하는 계약이다 — 2026-09-12에 알림을 Redis에서 MySQL 발행물로 옮긴 이유가 이것이라,
 * 이 단언이 그 이전(초안)으로 되돌아가는 변경을 잡는다.
 *
 * <p><b>H2를 버리고 실제 MySQL로 옮겼다.</b> 예전에는 빈 H2 위에서 돌면서 DB를 건드리는 기동
 * 경로를 목으로 끊어 두었는데, 그러면 "기동 시 DB 경로가 깨져 있어도 통과하는" 스모크 테스트가
 * 된다. 지금은 기동이 발행물 포인터를 실제로 읽어야 하므로 목으로 끊을 자리가 없고, 끊으면 이
 * 파일이 검증하는 것이 사라진다. #404의 "H2로 대체하지 않는다" 방침과도 같은 방향이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SolplyServerApplicationTests extends MySqlContainerSupport {

    /** 이름은 베이스·다른 IT와 반드시 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void smokeProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
    }

    @MockBean
    S3Presigner s3Presigner;

    @MockBean
    S3Client s3Client;

    /** 프로파일이 Redis를 통째로 빼므로 이 둘은 목이 아니면 주입할 것이 없다 */
    @MockBean
    RedisTemplate<String, String> redisTemplate;

    @MockBean
    CacheService cacheService;

    @Autowired
    SnapshotInstaller installer;

    @Test
    void contextLoads() {
    }

    /**
     * <b>Redis가 없어도 기동이 스냅샷을 복원한다.</b> 발행물이 없으면 기동이 최초 발행을 짓고
     * 그것을 설치하므로, 여기까지 왔다는 것은 발행·채택·복원 셋이 모두 돌았다는 뜻이다.
     *
     * <p>설치 id가 음수로 남아 있으면 인스턴스가 <b>빈 목록</b>을 서빙한다 — 기동이 그것을 막는
     * 것이 계약이라 값으로 못 박는다.
     */
    @Test
    void Redis_없이도_기동이_목록_스냅샷을_복원한다() {
        assertThat(installer.installedPublicationId())
                .as("기동 복원이 끝났으면 설치된 발행물 id가 있다")
                .isNotNegative();
    }
}
