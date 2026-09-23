package com.closetos.search.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReembeddingJob(
        UUID id,
        UUID wardrobeId,
        EmbeddingModel model,
        String state,
        int total,
        int queued,
        int running,
        int succeeded,
        int skipped,
        int failed,
        Map<String, Integer> failureCounts,
        Instant requestedAt,
        Instant completedAt) {}
