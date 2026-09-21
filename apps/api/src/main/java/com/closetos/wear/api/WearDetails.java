package com.closetos.wear.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WearDetails(
        UUID id,
        UUID outfitId,
        String outfitName,
        LocalDate wornOn,
        String notes,
        String context,
        List<UUID> garmentIds,
        Instant createdAt) {}
