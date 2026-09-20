package com.closetos.intelligence.api;

import java.util.Map;
import java.util.UUID;

public record AnalysisDocument(
        UUID imageId,
        String pipelineVersion,
        String modelId,
        String modelVersion,
        String promptVersion,
        Map<String, SuggestedValue> suggestions) {}
