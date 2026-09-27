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

    public String imagePrefix() {
        String[] parts = sourceKey.split("/", -1);
        if (parts.length != 7
                || !parts[0].equals("users")
                || !parts[2].equals("garments")
                || !parts[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !parts[3].equals(garmentId.toString())
                || !parts[4].equals("images")
                || !parts[5].equals(imageId.toString())
                || !parts[6].startsWith("original."))
            throw new IllegalStateException("Invalid processing image scope.");
        String prefix = String.join("/", java.util.Arrays.copyOf(parts, 6)) + "/";
        if (!outputPrefix.equals(prefix + "pipelines/" + pipelineVersion + "/"))
            throw new IllegalStateException("Invalid processing output scope.");
        return prefix;
    }
}
