package com.closetos.insights.application;

import static com.closetos.insights.application.RecommendationFixtures.piece;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.closetos.garment.api.GarmentCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicateRankerTest {
    private final DuplicateRanker ranker = new DuplicateRanker();

    @Test
    void closeRecordedColoursAndRelatedEmbeddingsProduceAnAdvisoryCanonicalPair() {
        var a = piece("Olive shirt", GarmentCategory.TOP, "#45664c");
        var b = piece("Sage shirt", GarmentCategory.TOP, "#4a6955");
        var first = ranker.rank(a, b, .95).orElseThrow();
        var reversed = ranker.rank(b, a, .95).orElseThrow();
        double distance = Math.sqrt((25.0 + 9 + 81) / (3 * 255 * 255.0));
        assertThat(first.score()).isCloseTo(.85 * .95 + .1 * (1 - distance) + .05, within(1e-12));
        assertThat(first).isEqualTo(reversed);
        assertThat(first.pair().reasons())
                .contains(
                        "Recorded colours are close.",
                        "A potential match to review, rather than a confirmed duplicate.");
        assertThat(first.pair().first().id().toString())
                .isLessThan(first.pair().second().id().toString());
    }

    @Test
    void hardCandidateRulesRejectWeakVectorsDifferentSlotsAndMissingOrDifferentColours() {
        var shirt = piece("Red shirt", GarmentCategory.TOP, "#ff0000");
        var related = piece("Another red shirt", GarmentCategory.TOP, "#ff0000");
        for (double affinity : new double[] {.8799, -1, Double.NaN, Double.POSITIVE_INFINITY, 1.1})
            assertThat(ranker.rank(shirt, related, affinity)).isEmpty();
        assertThat(ranker.rank(shirt, related, .88)).isPresent();
        assertThat(ranker.rank(shirt, shirt, 1)).isEmpty();
        assertThat(ranker.rank(shirt, piece("Red shoes", GarmentCategory.SHOES, "#ff0000"), 1))
                .isEmpty();
        assertThat(ranker.rank(shirt, piece("Blue shirt", GarmentCategory.TOP, "#0000ff"), 1))
                .isEmpty();
        assertThat(ranker.rank(shirt, piece("Unknown colour", GarmentCategory.TOP, null), 1))
                .isEmpty();
        assertThat(ranker.rank(shirt, piece("Bad legacy hex", GarmentCategory.TOP, "invalid"), 1))
                .isEmpty();
    }

    @Test
    void knownColourLabelsCanMatchWithoutInventingRangesForUnknownColours() {
        var gray =
                piece(
                        "Gray shirt",
                        GarmentCategory.TOP,
                        null,
                        null,
                        " Gray ",
                        null,
                        List.of(),
                        List.of());
        var grey =
                piece(
                        "Grey shirt",
                        GarmentCategory.TOP,
                        null,
                        null,
                        "grey",
                        null,
                        List.of(),
                        List.of());
        assertThat(ranker.rank(gray, grey, 1).orElseThrow().pair().reasons())
                .contains("The pieces share the same recorded colour label.");
        assertThat(ranker.rank(gray, piece("Unlabelled", GarmentCategory.TOP, null), 1)).isEmpty();
        var blue =
                piece("Blue", GarmentCategory.TOP, null, null, "blue", null, List.of(), List.of());
        assertThat(ranker.rank(gray, blue, 1)).isEmpty();
    }

    @Test
    void relatedCategoriesRequireAKnownSharedLayerType() {
        var top =
                piece(
                        "Cardigan",
                        GarmentCategory.TOP,
                        "cardigan",
                        "#444444",
                        null,
                        null,
                        List.of(),
                        List.of());
        var outer =
                piece(
                        "Another cardigan",
                        GarmentCategory.OUTERWEAR,
                        " CARDIGAN ",
                        "#444444",
                        null,
                        null,
                        List.of(),
                        List.of());
        var ranked = ranker.rank(top, outer, 1).orElseThrow();
        assertThat(ranked.score()).isCloseTo(.85 + .1 + .035, within(1e-12));
        assertThat(ranked.pair().reasons())
                .contains("The pieces share a layer type across related categories.");
        assertThat(ranker.rank(piece("Plain shirt", GarmentCategory.TOP, "#444444"), outer, 1))
                .isEmpty();
    }
}
