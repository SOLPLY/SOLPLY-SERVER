package org.sopt.solply_server.global.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class ReasonGenerationService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private final String chatModel;

    public ReasonGenerationService(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String chatModel,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    // OpenAI json_schema structured output: 루트는 반드시 object여야 하므로 reasons 배열로 래핑
    private static final Map<String, Object> PLACE_RESPONSE_FORMAT = Map.of(
            "type", "json_schema",
            "json_schema", Map.of(
                    "name", "place_reasons",
                    "strict", true,
                    "schema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "reasons", Map.of(
                                            "type", "array",
                                            "items", Map.of("type", "string")
                                    )
                            ),
                            "required", List.of("reasons"),
                            "additionalProperties", false
                    )
            )
    );

    private static final Map<String, Object> COURSE_RESPONSE_FORMAT = Map.of(
            "type", "json_schema",
            "json_schema", Map.of(
                    "name", "course_reasons",
                    "strict", true,
                    "schema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "reasons", Map.of(
                                            "type", "array",
                                            "items", Map.of("type", "string")
                                    )
                            ),
                            "required", List.of("reasons"),
                            "additionalProperties", false
                    )
            )
    );

    public List<String> generateReasons(String query, String userName, List<PlaceContext> places) {
        String prompt = buildPrompt(query, userName, places);

        Map<String, Object> requestBody = Map.of(
                "model", chatModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "response_format", PLACE_RESPONSE_FORMAT
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

            JsonNode reasons = objectMapper.readTree(content).get("reasons");
            List<String> result = new ArrayList<>();
            reasons.forEach(node -> result.add(node.asText()));
            return result;
        } catch (Exception e) {
            log.warn("추천 이유 생성 실패, 빈 문자열로 대체합니다. error={}", e.getMessage());
            return places.stream().map(p -> "").toList();
        }
    }

    private String buildPrompt(String query, String userName, List<PlaceContext> places) {
        return """
                당신은 장소 추천 전문가입니다.
                아래 사용자 질문과 장소 정보를 바탕으로, %s님께 각 장소를 추천하는 이유를 한 문장씩 작성해주세요.
                장소 순서와 개수를 반드시 유지하여 reasons 배열에 담아 응답하세요.

                사용자 질문: %s

                %s
                """.formatted(userName, query, buildPlacesInfo(places));
    }

    private String buildPlacesInfo(List<PlaceContext> places) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            PlaceContext p = places.get(i);
            sb.append("장소 ").append(i + 1).append("\n");
            sb.append("- 이름: ").append(p.name()).append("\n");
            sb.append("- 동네: ").append(p.townName()).append("\n");
            sb.append("- 장소 정보: ").append(p.retrievalText()).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public List<String> generateCourseReasons(String query, String userName, List<CourseContext> courses) {
        String prompt = buildCoursePrompt(query, userName, courses);

        Map<String, Object> requestBody = Map.of(
                "model", chatModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "response_format", COURSE_RESPONSE_FORMAT
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

            JsonNode reasons = objectMapper.readTree(content).get("reasons");
            List<String> result = new ArrayList<>();
            reasons.forEach(node -> result.add(node.asText()));
            return result;
        } catch (Exception e) {
            log.warn("코스 추천 이유 생성 실패, 빈 문자열로 대체합니다. error={}", e.getMessage());
            return courses.stream().map(c -> "").toList();
        }
    }

    private String buildCoursePrompt(String query, String userName, List<CourseContext> courses) {
        return """
                당신은 코스 추천 전문가입니다.
                아래 사용자 질문과 코스 정보를 바탕으로, %s님께 각 코스를 추천하는 이유를 한 문장씩 작성해주세요.
                코스 순서와 개수를 반드시 유지하여 reasons 배열에 담아 응답하세요.

                사용자 질문: %s

                %s
                """.formatted(userName, query, buildCoursesInfo(courses));
    }

    private String buildCoursesInfo(List<CourseContext> courses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < courses.size(); i++) {
            CourseContext c = courses.get(i);
            sb.append("코스 ").append(i + 1).append("\n");
            sb.append("- 이름: ").append(c.name()).append("\n");
            sb.append("- 동네: ").append(c.townName()).append("\n");
            sb.append("- 코스 정보: ").append(c.retrievalText()).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public record PlaceContext(
            String name,
            String townName,
            String retrievalText
    ) {}

    public record CourseContext(
            String name,
            String townName,
            String retrievalText
    ) {}
}
