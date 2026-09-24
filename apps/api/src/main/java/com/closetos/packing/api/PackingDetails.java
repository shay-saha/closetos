package com.closetos.packing.api;

import com.closetos.garment.api.GarmentDetails;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PackingDetails(
        UUID id,
        @JsonUnwrapped PackingTrip trip,
        long version,
        boolean manualOverride,
        PackingSolution plan,
        boolean stale,
        List<String> explanations,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt) {
    public record Item(GarmentDetails garment, ItemStatus status) {}

    public enum ItemStatus {
        TO_PACK,
        PACKED
    }

    public record Summary(
            UUID id,
            String name,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            String locationText,
            long version,
            PackingSolution.Status status,
            int itemCount,
            int packedCount,
            Instant createdAt) {}

    public record Page(List<Summary> items, String nextCursor) {}
}
