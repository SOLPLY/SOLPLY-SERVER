package org.sopt.solply_server.global.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 전역 구성의 준비 재실행 — <b>호출 안에서 전량을 짓고, 원본은 건드리지 않는다.</b>
 *
 * <p>여기서 무는 가장 중요한 것은 <b>같은 번호로 다시 태워도 준비 비용이 계측에 남는가</b>다.
 * 설치자의 단조 가드는 번호가 그대로면 마지막 참조 대입만 건너뛰는데, 그 앞의 전량 읽기와
 * 정렬은 실제로 다 일어난다. 그 비용을 설치 성공에만 달아 두면 이 구성의 준비가 통째로 0으로
 * 빠지고, 전역 스냅샷이 공짜로 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("bench")
class BenchPrepResetGlobalIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void benchGlobalProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        registry.add("solply.place-list.list-source", () -> "GLOBAL_SNAPSHOT");
        registry.add("solply.bench.enabled", () -> "true");
    }

    private static final String PATH = "/bench/prep/reset";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * <b>같은 번호로 다시 태워도 builds와 prepare 타이머가 오른다.</b> 이것이 이 파일의 핵심이다 —
     * 설치가 거절돼도 준비는 실제로 일어났고, 비교가 재려는 값이 바로 그 몫이다.
     */
    @Test
    void 같은_번호로_다시_태워도_전역_준비가_계측된다() throws Exception {
        double buildsBefore = counter("solply.global.snapshot.builds");
        long timedBefore = prepareGlobalCount();
        JsonNode metadataBefore = snapshotMetadata();

        JsonNode response = reset("{\"dryRun\":false}");

        assertThat(response.get("action").asText()).isEqualTo("rebuild_global");
        assertThat(counter("solply.global.snapshot.builds"))
                .as("설치가 거절돼도 지은 것은 지은 것이다")
                .isGreaterThan(buildsBefore);
        assertThat(prepareGlobalCount())
                .as("준비 소요도 함께 기록된다")
                .isGreaterThan(timedBefore);
        assertThat(snapshotMetadata())
                .as("전역 번호는 그대로다 — 준비를 태우려고 번호를 올리지 않는다")
                .isEqualTo(metadataBefore);
    }

    /** 준비가 호출 안에서 끝나므로 측정창을 정할 두 필드가 반드시 실려야 한다. */
    @Test
    void 준비가_호출_안에서_끝났음을_응답이_말한다() throws Exception {
        JsonNode response = reset("{\"dryRun\":false}");

        assertThat(response.get("preparedDuringCall").asBoolean()).isTrue();
        assertThat(response.get("prepareMillis").isNull()).isFalse();
        assertThat(response.get("prepareMillis").asLong()).isGreaterThanOrEqualTo(0L);
    }

    /** 계약의 핵심 — 번호를 올리지 않는다. 오르면 진행 중인 커서가 전부 끊긴다. */
    @Test
    void 동네_번호를_올리지_않는다() throws Exception {
        long townVersionsBefore = townVersionSum();

        JsonNode response = reset("{\"dryRun\":false}");

        assertThat(response.get("versionsBumped").asInt()).isZero();
        assertThat(townVersionSum()).isEqualTo(townVersionsBefore);
    }

    /** dryRun은 짓지 않는다 — 게이트 확인이 라운드를 오염시키면 안 된다. */
    @Test
    void dryRun은_아무것도_짓지_않는다() throws Exception {
        double buildsBefore = counter("solply.global.snapshot.builds");

        JsonNode response = reset("{\"dryRun\":true}");

        assertThat(response.get("preparedDuringCall").asBoolean()).isFalse();
        assertThat(response.get("prepareMillis").isNull()).isTrue();
        assertThat(counter("solply.global.snapshot.builds")).isEqualTo(buildsBefore);
    }

    /** 본문 없이 불러도 200이어야 한다 — 도구가 빈 POST로 확인한다. */
    @Test
    void 본문_없이_불러도_답한다() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    // === 픽스처 ===

    private JsonNode reset(String body) throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as("본문: %s", raw).isEqualTo(200);
        return objectMapper.readTree(raw);
    }

    private double counter(String name) {
        return meterRegistry.find(name).counter() == null
                ? 0.0
                : meterRegistry.find(name).counter().count();
    }

    private long prepareGlobalCount() {
        return meterRegistry.find("solply.place.list.prepare").tag("kind", "global")
                .timer().count();
    }

    private JsonNode snapshotMetadata() {
        return objectMapper.valueToTree(jdbcTemplate.queryForList(
                "SELECT revision, cursor_version FROM place_list_snapshot_metadata WHERE id = 1"));
    }

    private long townVersionSum() {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(version), 0) FROM place_list_town_versions", Long.class);
        return sum == null ? 0L : sum;
    }
}
