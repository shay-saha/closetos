package com.closetos.insights.application;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentMetadata;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.media.api.ProcessingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class RecommendationFixtures {
    private RecommendationFixtures() {}

    static GarmentDetails piece(
            String name,
            GarmentCategory category,
            String subcategory,
            String hex,
            String colourName,
            String formality,
            List<String> seasons,
            List<String> styles) {
        var metadata =
                new GarmentMetadata(
                        name,
                        category,
                        subcategory,
                        null,
                        null,
                        colourName,
                        hex,
                        List.of(),
                        null,
                        null,
                        null,
                        formality,
                        seasons,
                        List.of(),
                        styles,
                        null,
                        null,
                        null,
                        null);
        return new GarmentDetails(
                UUID.randomUUID(),
                metadata,
                GarmentStatus.AVAILABLE,
                ProcessingStatus.READY,
                0,
                null,
                null,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null);
    }

    static GarmentDetails piece(String name, GarmentCategory category, String hex) {
        return piece(name, category, null, hex, null, null, List.of(), List.of());
    }

    static GarmentDetails state(
            GarmentDetails garment, GarmentStatus status, ProcessingStatus processing) {
        return new GarmentDetails(
                garment.id(),
                garment.metadata(),
                status,
                processing,
                garment.wearCount(),
                garment.lastWornAt(),
                garment.costPerWear(),
                garment.version(),
                garment.createdAt(),
                garment.updatedAt(),
                garment.assets());
    }
}
