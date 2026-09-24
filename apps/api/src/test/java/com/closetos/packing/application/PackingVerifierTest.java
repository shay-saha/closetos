package com.closetos.packing.application;

import static com.closetos.packing.application.PackingFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingTrip;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PackingVerifierTest {
    private final PackingRules rules = new PackingRules();
    private final PackingVerifier verifier = new PackingVerifier();
    private final com.closetos.garment.api.GarmentDetails top =
            piece("Top", GarmentCategory.TOP, "mild");
    private final com.closetos.garment.api.GarmentDetails bottom =
            piece("Bottom", GarmentCategory.BOTTOM, "mild");
    private final com.closetos.garment.api.GarmentDetails dress =
            piece("Dress", GarmentCategory.DRESS, "mild");
    private final com.closetos.garment.api.GarmentDetails shoes =
            piece("Shoes", GarmentCategory.SHOES, "mild");

    @Test
    void acceptsCompleteDressAndSeparatesOutfitsWithinTheHardMaximum() {
        var problem = rules.prepare(trip(2, 4, 0, 2), List.of(top, bottom, dress, shoes)).problem();
        var result =
                solution(
                        List.of(top, bottom, dress, shoes),
                        List.of(List.of(dress, shoes), List.of(top, bottom, shoes)));
        assertThatCode(() -> verifier.verify(problem, result)).doesNotThrowAnyException();
        assertThat(verifier.explanations(problem, result))
                .anyMatch(text -> text.contains("Selected 4 unique pieces"))
                .anyMatch(text -> text.contains("proved this capsule optimal"));
        var feasible =
                new PackingSolution(
                        PackingSolution.Status.FEASIBLE,
                        result.selectedGarments(),
                        result.outfits(),
                        List.of());
        assertThat(verifier.explanations(problem, feasible))
                .anyMatch(text -> text.contains("did not prove optimality"));
    }

    @Test
    void rejectsMaximumOverflowMissingRequiredExcludedAndUnownedPieces() {
        var problem = rules.prepare(trip(1, 2, 0, 1), List.of(top, bottom, shoes)).problem();
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        problem,
                                        solution(
                                                List.of(top, bottom, shoes),
                                                List.of(List.of(top, bottom, shoes)))))
                .hasMessageContaining("Maximum garment count");
        var requiredTrip =
                trip(
                        1,
                        5,
                        0,
                        1,
                        MILD,
                        Set.of(top.id()),
                        Set.of(),
                        List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                        List.of());
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        rules.prepare(requiredTrip, List.of(top, dress, shoes))
                                                .problem(),
                                        solution(
                                                List.of(dress, shoes),
                                                List.of(List.of(dress, shoes)))))
                .hasMessageContaining("required piece");
        var excludedTrip =
                trip(
                        1,
                        5,
                        0,
                        1,
                        MILD,
                        Set.of(),
                        Set.of(dress.id()),
                        List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                        List.of());
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        rules.prepare(excludedTrip, List.of(dress, shoes))
                                                .problem(),
                                        solution(
                                                List.of(dress, shoes),
                                                List.of(List.of(dress, shoes)))))
                .hasMessageContaining("excluded piece");
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        rules.prepare(trip(1, 5, 0, 1), List.of(dress)).problem(),
                                        solution(
                                                List.of(dress, shoes),
                                                List.of(List.of(dress, shoes)))))
                .hasMessageContaining("reviewed, available");
    }

    @Test
    void neverAcceptsIncompleteConflictingOrDuplicateOutfits() {
        var problem = rules.prepare(trip(1, 5, 0, 1), List.of(top, bottom, dress, shoes)).problem();
        for (var invalid :
                List.of(
                        List.of(top, shoes),
                        List.of(top, bottom),
                        List.of(dress, top, shoes),
                        List.of(dress, bottom, shoes),
                        List.of(dress, shoes, shoes)))
            assertThatThrownBy(
                            () ->
                                    verifier.verify(
                                            problem,
                                            solution(
                                                    List.of(top, bottom, dress, shoes),
                                                    List.of(invalid))))
                    .isInstanceOf(PackingVerifier.InvalidPackingSolution.class);
        var result = solution(List.of(dress, shoes), List.of(List.of(dress, shoes)));
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        problem,
                                        new PackingSolution(
                                                PackingSolution.Status.OPTIMAL,
                                                List.of(dress.id(), shoes.id(), shoes.id()),
                                                result.outfits(),
                                                List.of())))
                .hasMessageContaining("must be unique");
    }

    @Test
    void requiresExactlyOneOutfitForEveryScheduledDemandIncludingFormalEvents() {
        var problem = rules.prepare(trip(2, 5, 0, 2), List.of(dress, shoes)).problem();
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        problem,
                                        solution(
                                                List.of(dress, shoes),
                                                List.of(List.of(dress, shoes)))))
                .hasMessageContaining("Every scheduled day");
        var repeated = new PackingSolution.ScheduledOutfit(0, List.of(dress.id(), shoes.id()));
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        problem,
                                        new PackingSolution(
                                                PackingSolution.Status.OPTIMAL,
                                                List.of(dress.id(), shoes.id()),
                                                List.of(repeated, repeated),
                                                List.of())))
                .hasMessageContaining("exactly once");
    }

    @Test
    void rewearCapacityResetsOnlyAfterTheScheduledLaundryOpportunity() {
        var result =
                solution(
                        List.of(dress, shoes),
                        List.of(
                                List.of(dress, shoes),
                                List.of(dress, shoes),
                                List.of(dress, shoes)));
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        rules.prepare(trip(3, 5, 0, 2), List.of(dress, shoes))
                                                .problem(),
                                        result))
                .hasMessageContaining("rewear allowance");
        assertThatCode(
                        () ->
                                verifier.verify(
                                        rules.prepare(trip(3, 5, 2, 2), List.of(dress, shoes))
                                                .problem(),
                                        result))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        rules.prepare(trip(3, 5, 3, 2), List.of(dress, shoes))
                                                .problem(),
                                        result))
                .hasMessageContaining("rewear allowance");
    }

    @Test
    void sameDayFormalEventConsumesAnotherClothingWearAndChecksItsFormality() {
        var trip =
                trip(
                        1,
                        5,
                        0,
                        1,
                        MILD,
                        Set.of(),
                        Set.of(),
                        List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                        List.of(new PackingTrip.FormalEvent("Dinner", START, null, "Formal")));
        var problem = rules.prepare(trip, List.of(dress, shoes)).problem();
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        problem,
                                        solution(
                                                List.of(dress, shoes),
                                                List.of(
                                                        List.of(dress, shoes),
                                                        List.of(dress, shoes)))))
                .hasMessageContaining("occasion or formality");
        var formalDress =
                PackingFixtures.piece(
                        "Formal dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of(),
                        List.of("mild"),
                        "Formal",
                        com.closetos.garment.api.GarmentStatus.AVAILABLE,
                        com.closetos.media.api.ProcessingStatus.READY,
                        0);
        var formalShoes =
                PackingFixtures.piece(
                        "Formal shoes",
                        GarmentCategory.SHOES,
                        null,
                        List.of(),
                        List.of(),
                        List.of("mild"),
                        "Formal",
                        com.closetos.garment.api.GarmentStatus.AVAILABLE,
                        com.closetos.media.api.ProcessingStatus.READY,
                        0);
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        rules.prepare(trip, List.of(formalDress, formalShoes))
                                                .problem(),
                                        solution(
                                                List.of(formalDress, formalShoes),
                                                List.of(
                                                        List.of(formalDress, formalShoes),
                                                        List.of(formalDress, formalShoes)))))
                .hasMessageContaining("rewear allowance");
    }

    @Test
    void infeasibleOrTimedOutRequestsNeverExposePretendCapsules() {
        var problem = rules.prepare(trip(1, 5, 0, 1), List.of(dress, shoes)).problem();
        for (var status :
                List.of(PackingSolution.Status.INFEASIBLE, PackingSolution.Status.TIME_LIMIT)) {
            assertThatCode(
                            () ->
                                    verifier.verify(
                                            problem,
                                            new PackingSolution(
                                                    status, List.of(), List.of(), List.of())))
                    .doesNotThrowAnyException();
            assertThatThrownBy(
                            () ->
                                    verifier.verify(
                                            problem,
                                            new PackingSolution(
                                                    status,
                                                    List.of(dress.id()),
                                                    List.of(),
                                                    List.of())))
                    .hasMessageContaining("must not contain a capsule");
        }
    }

    @Test
    void coldAndRainyOutfitsNeedARecordedCompatibleOuterLayer() {
        var coldDress = piece("Cold dress", GarmentCategory.DRESS, "cold");
        var coldShoes = piece("Cold shoes", GarmentCategory.SHOES, "cold");
        var coat = piece("Rain coat", GarmentCategory.OUTERWEAR, "cold", "waterproof");
        var weather =
                new PackingTrip.Weather(
                        null,
                        null,
                        Set.of(
                                com.closetos.insights.api.RecommendationWeather.COLD,
                                com.closetos.insights.api.RecommendationWeather.RAIN),
                        null);
        var trip =
                trip(
                        1,
                        5,
                        0,
                        1,
                        weather,
                        Set.of(),
                        Set.of(),
                        List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                        List.of());
        var problem = rules.prepare(trip, List.of(coldDress, coldShoes, coat)).problem();
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        problem,
                                        solution(
                                                List.of(coldDress, coldShoes, coat),
                                                List.of(List.of(coldDress, coldShoes)))))
                .hasMessageContaining("outer layer");
        assertThatCode(
                        () ->
                                verifier.verify(
                                        problem,
                                        solution(
                                                List.of(coldDress, coldShoes, coat),
                                                List.of(List.of(coldDress, coldShoes, coat)))))
                .doesNotThrowAnyException();
    }
}
