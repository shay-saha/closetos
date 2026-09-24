package com.closetos.insights.application;

import static com.closetos.insights.application.InsightFixtures.TODAY;
import static com.closetos.insights.application.InsightFixtures.defaults;
import static com.closetos.insights.application.InsightFixtures.piece;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.HistoryInsights.OwnershipBasis;
import com.closetos.insights.api.HistoryInsights.SeasonCompatibility;
import com.closetos.insights.api.InsightSeason;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForgottenRankerTest {
    private final ForgottenRanker ranker = new ForgottenRanker(defaults());

    @Test
    void recordedHistoryAndSeasonHaveBoundedExplainableWeights() {
        var worn =
                ranker.rank(
                                piece("Worn once", 1, "90", "GBP"),
                                TODAY,
                                ZoneId.of("UTC"),
                                InsightSeason.AUTUMN)
                        .orElseThrow();
        var never =
                ranker.rank(
                                piece("No recorded wears", 0, "90", "GBP"),
                                TODAY,
                                ZoneId.of("UTC"),
                                InsightSeason.AUTUMN)
                        .orElseThrow();
        assertThat(worn.score()).isCloseTo(5.0 / 6, within(1e-12));
        assertThat(never.score()).isEqualTo(1);
        assertThat(worn.piece().daysSinceLastWear()).isEqualTo(180);
        assertThat(never.piece().daysSinceLastWear()).isNull();
        assertThat(never.piece().reasons()).contains("No wear has been recorded.");
        assertThat(worn.piece().seasonCompatibility()).isEqualTo(SeasonCompatibility.MATCH);
        assertThat(score(piece("Frequently worn", 10, "90", "GBP"))).isLessThan(worn.score());
    }

    @Test
    void missingSeasonIsNeutralAndDoesNotClaimASeasonalMatch() {
        var unknown = varied(List.of(), GarmentStatus.AVAILABLE);
        var mismatch = varied(List.of("summer"), GarmentStatus.AVAILABLE);
        var fall = varied(List.of(" FALL "), GarmentStatus.AVAILABLE);
        assertThat(score(fall)).isEqualTo(5.0 / 6);
        assertThat(score(unknown)).isCloseTo(4.5 / 6, within(1e-12));
        assertThat(score(mismatch)).isCloseTo(4.0 / 6, within(1e-12));
        var noSeason = ranker.rank(fall, TODAY, ZoneId.of("UTC"), null).orElseThrow();
        assertThat(noSeason.score()).isEqualTo(score(unknown));
        assertThat(noSeason.piece().seasonCompatibility())
                .isEqualTo(SeasonCompatibility.NOT_REQUESTED);
        assertThat(noSeason.piece().reasons())
                .contains("No season selected; season compatibility was not inferred.");
        for (var season : InsightSeason.values())
            assertThat(season.matches(List.of("year-round"))).isTrue();
    }

    @Test
    void unavailablePiecesAreDemotedAndArchivedPiecesAreExcluded() {
        double available = score(varied(List.of("autumn"), GarmentStatus.AVAILABLE));
        assertThat(score(varied(List.of("autumn"), GarmentStatus.LAUNDRY)))
                .isCloseTo(available * .35, within(1e-12));
        assertThat(score(varied(List.of("autumn"), GarmentStatus.PACKED)))
                .isCloseTo(available * .15, within(1e-12));
        assertThat(score(varied(List.of("autumn"), GarmentStatus.LENT)))
                .isCloseTo(available * .1, within(1e-12));
        assertThat(
                        ranker.rank(
                                varied(List.of("autumn"), GarmentStatus.ARCHIVED),
                                TODAY,
                                ZoneId.of("UTC"),
                                null))
                .isEmpty();
    }

    @Test
    void eligibilityChecksOwnershipAndLastWearAtTheirInclusiveBoundaries() {
        var boundary =
                piece(
                        "Boundary",
                        1,
                        TODAY.minusDays(30),
                        TODAY.minusDays(90),
                        List.of(),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        assertThat(ranker.rank(boundary, TODAY, ZoneId.of("UTC"), null)).isPresent();
        for (var ineligible :
                List.of(
                        piece(
                                "New",
                                0,
                                null,
                                TODAY.minusDays(89),
                                List.of(),
                                GarmentStatus.AVAILABLE,
                                null,
                                null),
                        piece(
                                "Recent wear",
                                1,
                                TODAY.minusDays(29),
                                TODAY.minusDays(90),
                                List.of(),
                                GarmentStatus.AVAILABLE,
                                null,
                                null),
                        piece(
                                "Unknown recency",
                                1,
                                null,
                                TODAY.minusDays(365),
                                List.of(),
                                GarmentStatus.AVAILABLE,
                                null,
                                null),
                        piece(
                                "Future date",
                                0,
                                null,
                                TODAY.plusDays(1),
                                List.of(),
                                GarmentStatus.AVAILABLE,
                                null,
                                null)))
            assertThat(ranker.rank(ineligible, TODAY, ZoneId.of("UTC"), null)).isEmpty();
    }

    @Test
    void absentPurchaseDateUsesTheAddedDateInTheOwnersTimezone() {
        var garment =
                piece(
                        "Unknown ownership",
                        0,
                        null,
                        null,
                        List.of(),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        var utc = ranker.rank(garment, TODAY, ZoneId.of("UTC"), null).orElseThrow().piece();
        var pacific =
                ranker.rank(garment, TODAY, ZoneId.of("America/Los_Angeles"), null)
                        .orElseThrow()
                        .piece();
        assertThat(utc.ownedSince()).isEqualTo("2026-01-01");
        assertThat(pacific.ownedSince()).isEqualTo("2025-12-31");
        assertThat(pacific.ownershipBasis()).isEqualTo(OwnershipBasis.ADDED_DATE);
        assertThat(pacific.reasons().getFirst()).contains("purchase date is unknown");
    }

    @Test
    void configurationCanChangeTheOrderingWithoutChangingHardEligibility() {
        var old =
                piece(
                        "Older purchase",
                        1,
                        TODAY.minusDays(40),
                        TODAY.minusDays(1000),
                        List.of(),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        var unused =
                piece(
                        "Longer unused",
                        1,
                        TODAY.minusDays(180),
                        TODAY.minusDays(180),
                        List.of(),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        var ownership =
                new ForgottenRanker(
                        new ForgottenRankingProperties(
                                90,
                                30,
                                new ForgottenRankingProperties.Weights(1, 0, 0, 0),
                                defaults().availability()));
        var recency =
                new ForgottenRanker(
                        new ForgottenRankingProperties(
                                90,
                                30,
                                new ForgottenRankingProperties.Weights(0, 1, 0, 0),
                                defaults().availability()));
        assertThat(ownership.rank(old, TODAY, ZoneId.of("UTC"), null).orElseThrow().score())
                .isGreaterThan(
                        ownership
                                .rank(unused, TODAY, ZoneId.of("UTC"), null)
                                .orElseThrow()
                                .score());
        assertThat(recency.rank(old, TODAY, ZoneId.of("UTC"), null).orElseThrow().score())
                .isLessThan(
                        recency.rank(unused, TODAY, ZoneId.of("UTC"), null).orElseThrow().score());
        var ancient =
                piece(
                        "Ancient",
                        Integer.MAX_VALUE,
                        TODAY.minusYears(100),
                        TODAY.minusYears(200),
                        List.of("autumn"),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        assertThat(score(ancient)).isBetween(0.0, 1.0);
    }

    private GarmentDetails varied(List<String> seasons, GarmentStatus status) {
        return piece(
                "Comparable piece",
                1,
                TODAY.minusDays(180),
                TODAY.minusDays(365),
                seasons,
                status,
                null,
                null);
    }

    private double score(GarmentDetails garment) {
        return ranker.rank(garment, TODAY, ZoneId.of("UTC"), InsightSeason.AUTUMN)
                .orElseThrow()
                .score();
    }
}
