package com.closetos.insights.api;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.search.api.EmbeddingModel;
import java.util.List;

public final class RecommendationInsights {
    private RecommendationInsights() {}

    public enum EmbeddingState {
        READY,
        PENDING,
        UNAVAILABLE,
        NOT_NEEDED
    }

    public record EmbeddingCoverage(
            EmbeddingState state, EmbeddingModel model, int eligibleCount, int indexedCount) {}

    public record Duplicates(
            int reviewedGarmentCount,
            int photoGarmentCount,
            int missingColourCount,
            int candidatePairCount,
            boolean truncated,
            EmbeddingCoverage embeddings,
            List<DuplicatePair> items,
            List<String> explanations) {}

    public record DuplicatePair(
            GarmentDetails first, GarmentDetails second, List<String> reasons) {}

    public record WorksWith(
            GarmentDetails source,
            PairingContext context,
            boolean sourceMatchesContext,
            int eligibleCount,
            EmbeddingCoverage embeddings,
            List<Pairing> items,
            List<String> explanations) {}

    public record Pairing(
            GarmentDetails garment,
            int wornTogetherCount,
            int savedTogetherCount,
            boolean semanticAffinityKnown,
            List<String> reasons) {}
}
