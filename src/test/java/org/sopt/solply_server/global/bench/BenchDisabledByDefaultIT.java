package org.sopt.solply_server.global.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * <b>기본 설정에서 벤치 통로는 서지 않는다.</b> 캐시를 비우고 전량 재빌드를 부르는 무인증
 * 통로라, 켜는 조건을 한 겹으로 두지 않았다.
 *
 * <p>여기가 무는 것은 <b>기본값</b>이다 — 프로파일도 프로퍼티도 주지 않은 컨텍스트에서
 * 빈이 없고, 경로가 인증 벽에 걸린다. 운영 설정에 {@code solply.bench.enabled} 키가 없으면
 * 프로파일이 실수로 켜져도 통로가 열리지 않는다.
 *
 * <p><b>바이트코드는 jar에 남는다.</b> {@code @Profile}·{@code @ConditionalOnProperty}는 빈
 * 등록을 막을 뿐 클래스를 지우지 않는다 — 그 사실을 알고 이 방식을 골랐고, 그 맞교환은
 * {@code docs/verification/2026-09-21-comparison-integration.md}에 적었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BenchDisabledByDefaultIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void defaultProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        // solply.bench.enabled 를 <b>주지 않는다</b> — 그것이 이 파일의 전부다
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;

    @Test
    void 기본_설정에서는_벤치_빈이_서지_않는다() {
        assertThat(context.getBeanNamesForType(BenchPrepResetController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(BenchPrepResetService.class)).isEmpty();
    }

    /** 통로가 닫혀 있다 — 인증 벽에 걸려 200이 아니다. */
    @Test
    void 기본_설정에서는_통로가_열리지_않는다() throws Exception {
        MvcResult result = mockMvc.perform(post("/bench/prep/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("무인증으로 200이 나오면 운영에 열린 통로가 된다")
                .isNotEqualTo(200);
        assertThat(result.getResponse().getStatus()).isIn(401, 403);
    }

    /** 프로퍼티가 없으면 보안 쪽 허용 체인도 서지 않는다 — 세 겹 중 마지막 겹이다. */
    @Test
    void 기본_설정에서는_벤치_보안_체인도_없다() {
        assertThat(context.containsBean("benchChain")).isFalse();
    }
}
