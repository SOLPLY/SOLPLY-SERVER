package org.sopt.solply_server.global.ai;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReasonGenerationService {

    private final ChatModel chatModel;

    private static final String PROMPT_TEMPLATE = """
            당신은 장소 추천 전문가입니다.
            아래 사용자 질문과 장소 정보를 바탕으로, {userName}님께 각 장소를 추천하는 이유를 한 문장씩 작성해주세요.

            사용자 질문: {query}

            {placesInfo}

            {format}
            """;

    public List<String> generateReasons(String query, String userName, List<PlaceContext> places) {
        BeanOutputConverter<List<String>> converter =
                new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});

        String placesInfo = buildPlacesInfo(places);

        PromptTemplate promptTemplate = new PromptTemplate(PROMPT_TEMPLATE);
        Prompt prompt = promptTemplate.create(java.util.Map.of(
                "query", query,
                "userName", userName,
                "placesInfo", placesInfo,
                "format", converter.getFormat()
        ));

        String response = chatModel.call(prompt).getResult().getOutput().getContent();
        return converter.convert(response);
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
