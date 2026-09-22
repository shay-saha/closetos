package com.closetos.search.api;

import java.util.Set;

public record EmbeddingModel(
        String provider,
        String modelId,
        String modelVersion,
        String pipelineVersion,
        int dimensions) {
    public EmbeddingModel {
        if (provider == null
                || !Set.of("bedrock", "local-clip").contains(provider)
                || modelId == null
                || modelId.isBlank()
                || modelId.length() > 512
                || modelVersion != null && modelVersion.length() > 200
                || pipelineVersion == null
                || pipelineVersion.isBlank()
                || pipelineVersion.length() > 100
                || !Set.of(256, 384, 512, 1024).contains(dimensions)
                || provider.equals("local-clip") && dimensions != 512
                || provider.equals("bedrock") && dimensions == 512)
            throw new IllegalArgumentException("Invalid embedding model identity.");
    }
}
