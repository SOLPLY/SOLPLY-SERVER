package org.sopt.solply_server.global.ai;

import java.util.OptionalDouble;

public class CosineSimilarityUtil {

    private CosineSimilarityUtil() {}

    public static OptionalDouble calculate(float[] a, float[] b) {
        if (a.length != b.length) {
            return OptionalDouble.empty();
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return OptionalDouble.of(0.0);
        return OptionalDouble.of(dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }
}
