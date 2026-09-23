package com.closetos.search.api;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.media.api.MediaAssets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WardrobeGraph(
        List<Node> nodes,
        List<Edge> edges,
        int eligibleCount,
        int indexedCount,
        boolean truncated,
        EmbeddingModel model,
        EmbeddingAvailability embeddingAvailability) {
    public enum EmbeddingAvailability {
        AVAILABLE,
        UNAVAILABLE
    }

    public record Node(
            UUID id,
            String name,
            GarmentCategory category,
            MediaAssets assets,
            String colour,
            int wearCount,
            LocalDate lastWornAt,
            BigDecimal costPerWear,
            String purchaseCurrency,
            boolean indexed) {}

    public record Edge(UUID source, UUID target, double weight) {}
}
