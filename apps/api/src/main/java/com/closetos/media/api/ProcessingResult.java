package com.closetos.media.api;

import java.util.Map;
import java.util.UUID;

public record ProcessingResult(
        UUID jobId,
        UUID imageId,
        String pipelineVersion,
        String sourceChecksumSha256,
        Map<String, AssetDescriptor> assets,
        String analysisKey,
        String analysisFailure,
        double foregroundFraction,
        String segmentationModel) {}
