package com.closetos.packing.application;

import static com.closetos.packing.application.PackingFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.packing.api.PackingTrip;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PackingObjectiveTest {
    private final PackingRules rules = new PackingRules();

    @Test
    void countsCompleteAlternativeOutfitsWithoutCountingTripDaysTwice() {
        var top1 = piece("Top one", GarmentCategory.TOP, "mild");
        var top2 = piece("Top two", GarmentCategory.TOP, "mild");
        var bottom = piece("Bottom", GarmentCategory.BOTTOM, "mild");
        var dress = piece("Dress", GarmentCategory.DRESS, "mild");
        var shoes1 = piece("Shoes one", GarmentCategory.SHOES, "mild");
        var shoes2 = piece("Shoes two", GarmentCategory.SHOES, "mild");
        var coat = piece("Coat", GarmentCategory.OUTERWEAR, "mild");
        var problem =
                rules.prepare(
                                trip(3, 10, 0, 3),
                                List.of(top1, top2, bottom, dress, shoes1, shoes2, coat))
                        .problem();
        var selected = problem.byId().keySet();
        assertThat(PackingObjective.contexts(problem)).hasSize(1);
        assertThat(
                        PackingObjective.possibleOutfits(
                                problem, problem.demands().getFirst(), selected))
                .isEqualTo(12);
        assertThat(
                        PackingObjective.possibleOutfits(
                                problem,
                                problem.demands().getFirst(),
                                Set.of(top1.id(), shoes1.id())))
                .isZero();
    }

    @Test
    void objectiveBalancesCountVarietyVersatilityAndActualWearHistory() {
        var forgotten =
                piece(
                        "Forgotten dress",
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
                        "Often worn dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        Integer.MAX_VALUE);
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var problem = rules.prepare(trip(1, 10, 0, 1), List.of(forgotten, worn, shoes)).problem();
        var weights = PackingObjective.Weights.defaults();
        assertThat(PackingObjective.cost(problem, Set.of(forgotten.id(), shoes.id()), weights))
                .isLessThan(PackingObjective.cost(problem, Set.of(worn.id(), shoes.id()), weights));
        assertThat(PackingObjective.cost(problem, Set.of(forgotten.id(), shoes.id()), weights))
                .isLessThan(PackingObjective.cost(problem, problem.byId().keySet(), weights));
        assertThat(PackingObjective.underuse(problem.byId().get(worn.id()))).isZero();
        assertThat(PackingObjective.underuse(problem.byId().get(forgotten.id()))).isEqualTo(100);
    }

    @Test
    void recordedRedundancyHasAPenaltyWhileUnknownDetailsDoNotInventDuplicates() {
        var a =
                piece(
                        "A dress",
                        GarmentCategory.DRESS,
                        "Shift",
                        List.of(),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var b =
                piece(
                        "B dress",
                        GarmentCategory.DRESS,
                        " shift ",
                        List.of(),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var unknown = piece("Unknown subtype", GarmentCategory.DRESS, "mild");
        var problem = rules.prepare(trip(1, 10, 0, 1), List.of(a, b, unknown)).problem();
        assertThat(PackingObjective.redundancyGroup(problem.byId().get(a.id())))
                .isEqualTo(PackingObjective.redundancyGroup(problem.byId().get(b.id())));
        assertThat(PackingObjective.redundancyGroup(problem.byId().get(unknown.id()))).isEmpty();
        var selected = Set.of(a.id(), b.id());
        assertThat(
                        PackingObjective.cost(
                                problem, selected, new PackingObjective.Weights(100, 0, 0, 0, 300)))
                .isEqualTo(500);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PackingObjective.Weights(0, 1, 1, 1, 1));
    }

    @Test
    void versatilityReflectsHardOccasionCoverageAndExtrasCannotInflateIt() {
        var dress =
                piece(
                        "Versatile dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of("office", "evening"),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var bag = piece("Bag", GarmentCategory.BAG);
        var trip =
                trip(
                        2,
                        10,
                        0,
                        2,
                        MILD,
                        Set.of(),
                        Set.of(),
                        List.of(
                                new PackingTrip.Occasion("Work", "Office", null, 1),
                                new PackingTrip.Occasion("Dinner", "Evening", null, 1)),
                        List.of());
        var problem = rules.prepare(trip, List.of(dress, bag)).problem();
        assertThat(PackingObjective.versatility(problem, problem.byId().get(dress.id())))
                .isEqualTo(2);
        assertThat(PackingObjective.versatility(problem, problem.byId().get(bag.id()))).isZero();
        assertThat(PackingObjective.contexts(problem)).hasSize(2);
    }
}
