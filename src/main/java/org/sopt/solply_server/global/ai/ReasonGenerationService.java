package org.sopt.solply_server.global.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public List<String> generateReasons(String query, String userName, List<PlaceContext> places) {
        String prompt = buildPrompt(query, userName, places);

        Map<String, Object> requestBody = Map.of(
                "model", chatModel,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

            return objectMapper.readValue(content.trim(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("추천 이유 생성 실패, 빈 문자열로 대체합니다. error={}", e.getMessage());
            return places.stream().map(p -> "").toList();
        }
    }

    private String buildPrompt(String query, String userName, List<PlaceContext> places) {
        return """
                당신은 장소 추천 전문가입니다.
                아래 사용자 질문과 장소 정보를 바탕으로, %s님께 각 장소를 추천하는 이유를 한 문장씩 작성해주세요.

                사용자 질문: %s

                %s

                반드시 아래 형식의 JSON 배열로만 응답하세요. 다른 텍스트는 포함하지 마세요.
                ["장소1 추천 이유", "장소2 추천 이유", "장소3 추천 이유"]
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

    public record PlaceContext(
            String name,
            String townName,
            String retrievalText
    ) {}
}
