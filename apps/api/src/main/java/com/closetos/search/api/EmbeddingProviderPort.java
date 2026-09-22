package com.closetos.search.api;

import java.util.List;

public interface EmbeddingProviderPort {
    ModelInfo model();

    VectorResult embed(Input input, ModelInfo expected);

    record Input(String text, String imageKey, String imageChecksumSha256, String imageBase64) {}

    record ModelInfo(String modelKey, EmbeddingModel model) {
        public ModelInfo {
            if (model == null || modelKey == null || !modelKey.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid embedding model identity.");
        }
    }

    record VectorResult(String modelKey, EmbeddingModel model, List<Double> vector) {
        public VectorResult {
            new ModelInfo(modelKey, model);
            if (vector == null
                    || vector.size() != model.dimensions()
                    || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value)))
                throw new IllegalArgumentException("Invalid embedding vector.");
            double norm = Math.sqrt(vector.stream().mapToDouble(value -> value * value).sum());
            if (!Double.isFinite(norm) || norm < 1e-12)
                throw new IllegalArgumentException("Embedding vector has no direction.");
            vector = vector.stream().map(value -> value / norm).toList();
        }
    }
}
