package com.closetos.insights.application;

import static com.closetos.insights.application.RecommendationFixtures.piece;
import static com.closetos.insights.application.RecommendationFixtures.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.InsightSeason;
import com.closetos.insights.api.PairingContext;
import com.closetos.insights.api.RecommendationWeather;
import com.closetos.media.api.ProcessingStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class PairingRankerTest {
    private static final PairingContext ANY = new PairingContext(null, null, null);
    private final PairingRanker ranker = new PairingRanker();

    @Test
    void allSixRankingSignalsHaveTestedWeightsAndExplainTheirRecordedEvidence() {
        var top =
                piece(
                        "Red top",
                        GarmentCategory.TOP,
                        null,
                        "#ff0000",
                        null,
                        "Casual",
                        List.of("winter"),
                        List.of("Classic", "linen"));
        var bottom =
                piece(
                        "Cyan trousers",
                        GarmentCategory.BOTTOM,
                        null,
                        "#00ffff",
                        null,
                        "casual",
                        List.of("winter"),
                        List.of("classic", "linen"));
        var ranked = ranker.rank(top, bottom, ANY, .9, 5, 3).orElseThrow();
        assertThat(ranked.score()).isCloseTo(.3 + .15 + .15 * .75 + .2 + .1 + .09, within(1e-12));
        assertThat(ranked.pairing().semanticAffinityKnown()).isTrue();
        assertThat(ranked.pairing().reasons())
                .contains(
                        "Adds a complementary garment slot.",
                        "Shares recorded style tags: classic, linen.",
                        "The recorded colours have complementary hues.",
                        "Recorded together in 5 wear entries.",
                        "Paired in 3 saved outfits.",
                        "Related features in the current garment embeddings.");
        assertThat(ranker.rank(top, bottom, ANY, .9, 500, 300).orElseThrow().score())
                .isEqualTo(ranked.score());
    }

    @Test
    void missingDataDoesNotProduceInventedStyleColourOrSemanticEvidence() {
        var top = piece("Unknown top", GarmentCategory.TOP, null);
        var bottom = piece("Unknown bottom", GarmentCategory.BOTTOM, null);
        var ranked = ranker.rank(top, bottom, ANY, null, 0, 0).orElseThrow();
        assertThat(ranked.score()).isEqualTo(.3);
        assertThat(ranked.pairing().semanticAffinityKnown()).isFalse();
        assertThat(ranked.pairing().reasons())
                .contains(
                        "Colour compatibility is unknown from the recorded details.",
                        "Style compatibility is incomplete because style tags are missing.",
                        "Semantic affinity is unavailable; this suggestion uses recorded metadata and history.");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 1.1})
            assertThat(ranker.rank(top, bottom, ANY, invalid, -1, -1).orElseThrow().score())
                    .isEqualTo(.3);
        var neutral =
                piece(
                        "Named neutral",
                        GarmentCategory.TOP,
                        null,
                        null,
                        "beige",
                        null,
                        List.of(),
                        List.of());
        assertThat(ColourCompatibility.pairing(neutral, bottom)).isEmpty();
    }

    @Test
    void availabilityAndReviewAreHardRulesThatHistoryCannotOverride() {
        var top = piece("Top", GarmentCategory.TOP, null);
        var bottom = piece("Bottom", GarmentCategory.BOTTOM, null);
        for (var status : GarmentStatus.values())
            if (status != GarmentStatus.AVAILABLE) {
                assertThat(
                                ranker.rank(
                                        top,
                                        state(bottom, status, ProcessingStatus.READY),
                                        ANY,
                                        1.0,
                                        100,
                                        100))
                        .isEmpty();
                assertThat(
                                ranker.rank(
                                        state(top, status, ProcessingStatus.READY),
                                        bottom,
                                        ANY,
                                        1.0,
                                        100,
                                        100))
                        .isEmpty();
            }
        assertThat(
                        ranker.rank(
                                top,
                                state(
                                        bottom,
                                        GarmentStatus.AVAILABLE,
                                        ProcessingStatus.READY_FOR_REVIEW),
                                ANY,
                                1.0,
                                100,
                                100))
                .isEmpty();
        assertThat(ranker.rank(top, top, ANY, 1.0, 100, 100)).isEmpty();
    }

    @Test
    void structuralConflictsAreExcludedButKnownLayerAndAccessorySlotsAreSupported() {
        var dress = piece("Dress", GarmentCategory.DRESS, null);
        assertThat(ranker.rank(dress, piece("Top", GarmentCategory.TOP, null), ANY, 1.0, 100, 100))
                .isEmpty();
        assertThat(
                        ranker.rank(
                                dress,
                                piece("Bottom", GarmentCategory.BOTTOM, null),
                                ANY,
                                1.0,
                                100,
                                100))
                .isEmpty();
        assertThat(
                        ranker.rank(
                                dress,
                                piece("Other", GarmentCategory.OTHER, null),
                                ANY,
                                1.0,
                                100,
                                100))
                .isEmpty();
        var cardigan =
                piece(
                        "Cardigan",
                        GarmentCategory.TOP,
                        "cardigan",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of());
        assertThat(ranker.rank(dress, cardigan, ANY, null, 0, 0)).isPresent();
        assertThat(
                        ranker.rank(
                                cardigan,
                                piece("Base shirt", GarmentCategory.TOP, null),
                                ANY,
                                null,
                                0,
                                0))
                .isPresent();
        var earrings =
                piece(
                        "Earrings",
                        GarmentCategory.JEWELLERY,
                        "earrings",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of());
        var necklace =
                piece(
                        "Necklace",
                        GarmentCategory.JEWELLERY,
                        "necklace",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of());
        assertThat(ranker.rank(earrings, necklace, ANY, null, 0, 0)).isPresent();
        assertThat(
                        ranker.rank(
                                earrings,
                                piece("Unclassified jewellery", GarmentCategory.JEWELLERY, null),
                                ANY,
                                null,
                                0,
                                0))
                .isEmpty();
    }

    @Test
    void requestedContextAppliesToBothPiecesAndMissingTagsAreNotMatches() {
        var top =
                piece(
                        "Winter top",
                        GarmentCategory.TOP,
                        null,
                        null,
                        null,
                        " Smart casual ",
                        List.of("winter"),
                        List.of("cold-weather"));
        var bottom =
                piece(
                        "All-season trousers",
                        GarmentCategory.BOTTOM,
                        null,
                        null,
                        null,
                        "smart casual",
                        List.of("all-season"),
                        List.of("COLD"));
        var context =
                new PairingContext(
                        InsightSeason.WINTER, "smart casual", RecommendationWeather.COLD);
        assertThat(ranker.rank(top, bottom, context, null, 0, 0).orElseThrow().pairing().reasons())
                .contains(
                        "Both pieces match the selected season tags.",
                        "Both pieces have the requested formality.",
                        "Both pieces have explicit tags for the requested weather assumption.");
        assertThat(
                        ranker.rank(
                                top,
                                bottom,
                                new PairingContext(InsightSeason.SUMMER, null, null),
                                null,
                                0,
                                0))
                .isEmpty();
        assertThat(ranker.rank(top, bottom, new PairingContext(null, "formal", null), null, 0, 0))
                .isEmpty();
        assertThat(
                        ranker.rank(
                                top,
                                bottom,
                                new PairingContext(null, null, RecommendationWeather.RAIN),
                                null,
                                0,
                                0))
                .isEmpty();
        var missing = piece("Unknown tags", GarmentCategory.BOTTOM, null);
        assertThat(ranker.rank(top, missing, context, 1.0, 100, 100)).isEmpty();
        var winterWithoutWeather =
                piece(
                        "Winter only",
                        GarmentCategory.BOTTOM,
                        null,
                        null,
                        null,
                        "smart casual",
                        List.of("winter"),
                        List.of());
        assertThat(
                        ranker.rank(
                                top,
                                winterWithoutWeather,
                                new PairingContext(null, null, RecommendationWeather.COLD),
                                null,
                                0,
                                0))
                .isEmpty();
    }

    @Test
    void styleJaccardAndRecordedColourRangesAreBoundedWithoutDoubleCountingTags() {
        var top =
                piece(
                        "Green top",
                        GarmentCategory.TOP,
                        null,
                        "#00ff00",
                        null,
                        null,
                        List.of(),
                        List.of("Classic", "classic", "linen"));
        var bottom =
                piece(
                        "Yellow trousers",
                        GarmentCategory.BOTTOM,
                        null,
                        "#ffff00",
                        null,
                        null,
                        List.of(),
                        List.of("classic", "minimal"));
        assertThat(ranker.rank(top, bottom, ANY, null, 0, 0).orElseThrow().score())
                .isCloseTo(.3 + .15 / 3 + .15 * .8, within(1e-12));
        assertThat(
                        ColourCompatibility.pairing(
                                        top, piece("Black", GarmentCategory.BOTTOM, "#000000"))
                                .orElseThrow()
                                .score())
                .isEqualTo(.8);
        assertThat(
                        ColourCompatibility.pairing(
                                        top,
                                        piece("Similar green", GarmentCategory.BOTTOM, "#00cc00"))
                                .orElseThrow()
                                .score())
                .isEqualTo(.7);
    }
}
