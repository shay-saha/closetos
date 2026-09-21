package com.closetos.outfit.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OutfitDetails(
        UUID id,
        @JsonUnwrapped OutfitMetadata metadata,
        List<OutfitItem> items,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
