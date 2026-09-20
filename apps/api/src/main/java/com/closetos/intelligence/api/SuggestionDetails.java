package com.closetos.intelligence.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SuggestionDetails(
        UUID id,
        UUID garmentId,
        UUID imageId,
        String modelId,
        String modelVersion,
        String promptVersion,
        String pipelineVersion,
        Map<String, SuggestedValue> suggestions,
        SuggestionStatus status,
        long version,
        Instant createdAt,
        Instant acceptedAt,
        Instant rejectedAt) {}
