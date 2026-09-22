package com.closetos.search.api;

import java.time.Instant;
import java.util.UUID;

public record EmbeddingStatus(
        UUID garmentId, String state, String failureCode, Instant updatedAt) {}
