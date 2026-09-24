package com.closetos.packing.application;

import static com.closetos.packing.application.PackingFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.RecommendationWeather;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingTrip;
import com.closetos.packing.infrastructure.OrToolsPackingSolver;
import com.closetos.packing.infrastructure.PackingSolverSettings;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrToolsPackingSolverTest {
    private final PackingRules rules = new PackingRules();
    private final PackingVerifier verifier = new PackingVerifier();
    private final OrToolsPackingSolver solver = nativeSolver(PackingObjective.Weights.defaults());

    @Test
    void nativeSolverFindsTheSmallestUsefulCapsuleAndPrefersTheUnderusedDress() {
        var forgotten =
                piece(
                        "Forgotten",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var worn =
                piece(
                        "Often worn",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        30);
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var problem = rules.prepare(trip(1, 5, 0, 1), List.of(worn, forgotten, shoes)).problem();
        var result = solver.solve(problem);
        assertThat(result.status()).isEqualTo(PackingSolution.Status.OPTIMAL);
        assertThat(result.selectedGarments()).containsExactlyInAnyOrder(forgotten.id(), shoes.id());
        verifier.verify(problem, result);
    }

    @Test
    void requiredPiecesStayIncludedWithinTheHardMaximum() {
        var required = piece("Required top", GarmentCategory.TOP, "mild");
        var bottom = piece("Bottom", GarmentCategory.BOTTOM, "mild");
        var dress = piece("Dress", GarmentCategory.DRESS, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var trip =
                trip(
                        1,
                        3,
                        0,
                        1,
                        MILD,
                        Set.of(required.id()),
                        Set.of(),
                        List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                        List.of());
        var problem = rules.prepare(trip, List.of(required, bottom, dress, shoes)).problem();
        var result = solver.solve(problem);
        assertThat(result.hasSolution()).isTrue();
        assertThat(result.selectedGarments()).hasSize(3).contains(required.id());
        verifier.verify(problem, result);
    }

    @Test
    void genuineInfeasibilityHasAConflictExplanationAndNoCapsule() {
        var dress = piece("Dress", GarmentCategory.DRESS, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var preparation = rules.prepare(trip(1, 1, 0, 1), List.of(dress, shoes));
        assertThat(preparation.hasBlockingWarnings()).isFalse();
        var result = solver.solve(preparation.problem());
        assertThat(result.status()).isEqualTo(PackingSolution.Status.INFEASIBLE);
        assertThat(result.selectedGarments()).isEmpty();
        assertThat(result.outfits()).isEmpty();
        assertThat(result.warnings())
                .anyMatch(warning -> warning.code().equals("MAXIMUM_GARMENTS"));
    }

    @Test
    void laundryMakesAnOtherwiseInfeasibleThreeDayTripFeasible() {
        var dress = piece("Dress", GarmentCategory.DRESS, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var wardrobe = List.of(dress, shoes);
        var noLaundry = solver.solve(rules.prepare(trip(3, 5, 0, 2), wardrobe).problem());
        assertThat(noLaundry.status()).isEqualTo(PackingSolution.Status.INFEASIBLE);
        assertThat(noLaundry.warnings())
                .anyMatch(warning -> warning.code().equals("REWEAR_ALLOWANCE"));
        var problem = rules.prepare(trip(3, 5, 2, 2), wardrobe).problem();
        var withLaundry = solver.solve(problem);
        assertThat(withLaundry.hasSolution()).isTrue();
        assertThat(withLaundry.selectedGarments()).hasSize(2);
        assertThat(withLaundry.outfits()).hasSize(3);
        verifier.verify(problem, withLaundry);
    }

    @Test
    void eachOccasionAndAdditionalFormalEventReceivesTheCorrectCompleteOutfit() {
        var casual =
                piece(
                        "City dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of("city"),
                        List.of("mild"),
                        "Casual",
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var formal =
                piece(
                        "Dinner dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of("dinner"),
                        List.of("mild"),
                        "Formal",
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var shoes =
                piece(
                        "Formal shoes",
                        GarmentCategory.SHOES,
                        null,
                        List.of(),
                        List.of("city", "dinner"),
                        List.of("mild"),
                        "Formal",
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var trip =
                trip(
                        1,
                        3,
                        0,
                        1,
                        MILD,
                        Set.of(),
                        Set.of(),
                        List.of(new PackingTrip.Occasion("City", "city", null, 1)),
                        List.of(new PackingTrip.FormalEvent("Dinner", START, "dinner", "Formal")));
        var problem = rules.prepare(trip, List.of(casual, formal, shoes)).problem();
        var result = solver.solve(problem);
        assertThat(result.hasSolution()).isTrue();
        assertThat(result.outfits().get(0).garmentIds())
                .containsExactlyInAnyOrder(casual.id(), shoes.id());
        assertThat(result.outfits().get(1).garmentIds())
                .containsExactlyInAnyOrder(formal.id(), shoes.id());
        verifier.verify(problem, result);
    }

    @Test
    void coldRainUsesAKnownWaterproofLayerAndNeverAnExcludedAlternative() {
        var dress = piece("Dress", GarmentCategory.DRESS, "cold");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "cold");
        var coat = piece("Waterproof coat", GarmentCategory.OUTERWEAR, "cold", "waterproof");
        var excluded = piece("Excluded coat", GarmentCategory.OUTERWEAR, "cold", "waterproof");
        var weather = new PackingTrip.Weather(3, 3, Set.of(RecommendationWeather.RAIN), null);
        var trip =
                trip(
                        1,
                        3,
                        0,
                        1,
                        weather,
                        Set.of(),
                        Set.of(excluded.id()),
                        List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                        List.of());
        var problem = rules.prepare(trip, List.of(dress, shoes, coat, excluded)).problem();
        var result = solver.solve(problem);
        assertThat(result.selectedGarments())
                .containsExactlyInAnyOrder(dress.id(), shoes.id(), coat.id());
        assertThat(result.outfits().getFirst().garmentIds()).contains(coat.id());
        verifier.verify(problem, result);
    }

    @Test
    void nativeWeightedOptimumMatchesExhaustiveFeasibleCapsulesIncludingUsefulSpares() {
        var dressA = piece("Dress A", GarmentCategory.DRESS, "mild");
        var dressB = piece("Dress B", GarmentCategory.DRESS, "mild");
        var top = piece("Top", GarmentCategory.TOP, "mild");
        var bottom = piece("Bottom", GarmentCategory.BOTTOM, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var layer = piece("Layer", GarmentCategory.OUTERWEAR, "mild");
        var wardrobe = List.of(dressA, dressB, top, bottom, shoes, layer);
        var problem = rules.prepare(trip(1, 4, 0, 1), wardrobe).problem();
        var weights = new PackingObjective.Weights(100, 1000, 0, 0, 0);
        var completeOutfits =
                List.of(
                        Set.of(dressA.id(), shoes.id()),
                        Set.of(dressB.id(), shoes.id()),
                        Set.of(top.id(), bottom.id(), shoes.id()));
        long exhaustive = Long.MAX_VALUE;
        for (int mask = 0; mask < 1 << wardrobe.size(); mask++) {
            var selected = new java.util.HashSet<UUID>();
            for (int index = 0; index < wardrobe.size(); index++)
                if ((mask & (1 << index)) != 0) selected.add(wardrobe.get(index).id());
            if (selected.size() > 4 || completeOutfits.stream().noneMatch(selected::containsAll))
                continue;
            exhaustive = Math.min(exhaustive, PackingObjective.cost(problem, selected, weights));
        }
        var result = nativeSolver(weights).solve(problem);
        assertThat(result.status()).isEqualTo(PackingSolution.Status.OPTIMAL);
        assertThat(PackingObjective.cost(problem, Set.copyOf(result.selectedGarments()), weights))
                .isEqualTo(exhaustive);
        assertThat(result.selectedGarments())
                .containsExactlyInAnyOrder(dressA.id(), dressB.id(), shoes.id(), layer.id());
    }

    @Test
    void identicalInputsProduceAnIdenticalOrderedCapsuleAndSchedule() {
        var first = piece("First dress", GarmentCategory.DRESS, "mild");
        var second = piece("Second dress", GarmentCategory.DRESS, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var problem = rules.prepare(trip(2, 3, 0, 2), List.of(second, first, shoes)).problem();
        assertThat(solver.solve(problem)).isEqualTo(solver.solve(problem));
    }

    private OrToolsPackingSolver nativeSolver(PackingObjective.Weights weights) {
        return new OrToolsPackingSolver(new PackingSolverSettings(10, 5, 2, weights), verifier);
    }
}
