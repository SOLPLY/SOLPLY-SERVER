package org.sopt.solply_server.global.ai;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    @Value("${spring.ai.openai.embedding.options.model}")
    private String modelName;

    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    public List<float[]> embedAll(List<String> texts) {
        return embeddingModel.embedForResponse(texts)
                .getResults()
                .stream()
                .map(e -> e.getOutput())
                .toList();
    }

    public String getModelName() {
        return modelName;
    }
}
