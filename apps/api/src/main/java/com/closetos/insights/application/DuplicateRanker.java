package com.closetos.insights.application;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.insights.api.RecommendationInsights.DuplicatePair;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DuplicateRanker {
    public Optional<RankedPair> rank(GarmentDetails first, GarmentDetails second, double affinity) {
        if (first.id().equals(second.id())
                || !Double.isFinite(affinity)
                || affinity < .88
                || affinity > 1
                || !GarmentStructure.duplicateCategory(first, second)) return Optional.empty();
        return ColourCompatibility.duplicate(first, second)
                .map(
                        colour -> {
                            boolean same =
                                    first.metadata().category() == second.metadata().category();
                            double score =
                                    .85 * affinity + .1 * colour.score() + .05 * (same ? 1 : .7);
                            var pair =
                                    first.id().toString().compareTo(second.id().toString()) < 0
                                            ? new DuplicatePair(
                                                    first,
                                                    second,
                                                    List.of(
                                                            "Related features in the current photo embeddings.",
                                                            same
                                                                    ? "The pieces have the same category."
                                                                    : "The pieces share a layer type across related categories.",
                                                            colour.reason(),
                                                            "A potential match to review, rather than a confirmed duplicate."))
                                            : new DuplicatePair(
                                                    second,
                                                    first,
                                                    List.of(
                                                            "Related features in the current photo embeddings.",
                                                            same
                                                                    ? "The pieces have the same category."
                                                                    : "The pieces share a layer type across related categories.",
                                                            colour.reason(),
                                                            "A potential match to review, rather than a confirmed duplicate."));
                            return new RankedPair(pair, score);
                        });
    }

    public record RankedPair(DuplicatePair pair, double score) {}
}
