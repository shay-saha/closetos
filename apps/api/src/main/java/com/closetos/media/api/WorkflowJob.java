package com.closetos.media.api;

import java.util.UUID;

public record WorkflowJob(
        UUID jobId,
        UUID imageId,
        UUID garmentId,
        UUID wardrobeId,
        String sourceKey,
        String outputPrefix,
        String mimeType,
        long expectedSize,
        String checksumSha256,
        String pipelineVersion,
        String requestId) {
    public String executionName() {
        return "garment-image-" + imageId + "-pipeline-" + pipelineVersion;
    }

    public String manifestKey() {
        return outputPrefix + "manifest.json";
    }
}
