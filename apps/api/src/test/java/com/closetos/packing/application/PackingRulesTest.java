package com.closetos.packing.application;

import static com.closetos.packing.application.PackingFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.InsightSeason;
import com.closetos.insights.api.RecommendationWeather;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.packing.api.PackingProblem.Slot;
import com.closetos.packing.api.PackingTrip;
import com.closetos.platform.api.DomainException;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PackingRulesTest {
    private final PackingRules rules = new PackingRules();

    @Test
    void schedulesEveryDayAndAdditionalFormalEventsWithLaundryBoundaries() {
        var occasions =
                List.of(
                        new PackingTrip.Occasion("City", " Sightseeing ", null, 2),
                        new PackingTrip.Occasion("Work", "Office", " Business ", 2));
        var event =
                new PackingTrip.FormalEvent("Dinner", START.plusDays(1), " Evening ", " Formal ");
        var preparation =
                rules.prepare(
                        trip(4, 20, 2, 3, MILD, Set.of(), Set.of(), occasions, List.of(event)),
                        List.of());
        var demands = preparation.problem().demands();
        assertThat(demands).hasSize(5);
        assertThat(demands)
                .extracting(demand -> demand.date())
                .containsExactly(
                        START,
                        START.plusDays(1),
                        START.plusDays(2),
                        START.plusDays(3),
                        START.plusDays(1));
        assertThat(demands)
                .extracting(demand -> demand.laundryPeriod())
                .containsExactly(0, 0, 1, 1, 0);
        assertThat(demands.get(0).occasionTag()).isEqualTo("Sightseeing");
        assertThat(demands.get(4).formality()).isEqualTo("Formal");
    }

    @Test
    void rejectsIncompleteSchedulesReversedDatesAndOutOfTripEvents() {
        assertThatThrownBy(() -> rules.prepare(trip(0, 5, 0, 1), List.of()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> rules.prepare(trip(32, 5, 0, 1), List.of()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                rules.prepare(
                                        trip(
                                                2,
                                                5,
                                                0,
                                                1,
                                                MILD,
                                                Set.of(),
                                                Set.of(),
                                                List.of(
                                                        new PackingTrip.Occasion(
                                                                "Everyday", null, null, 1)),
                                                List.of()),
                                        List.of()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("every trip day");
        assertThatThrownBy(
                        () ->
                                rules.prepare(
                                        trip(
                                                1,
                                                5,
                                                0,
                                                1,
                                                MILD,
                                                Set.of(),
                                                Set.of(),
                                                List.of(
                                                        new PackingTrip.Occasion(
                                                                "Everyday", null, null, 1)),
                                                List.of(
                                                        new PackingTrip.FormalEvent(
                                                                "Later",
                                                                START.plusDays(1),
                                                                null,
                                                                "Formal"))),
                                        List.of()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("within the trip");
    }

    @Test
    void temperatureRangeRequiresEveryIntersectingBandWithoutSeasonInference() {
        assertThat(
                        PackingRules.thermalRequirements(
                                new PackingTrip.Weather(10, 24, Set.of(), null)))
                .containsExactly(RecommendationWeather.MILD);
        assertThat(PackingRules.thermalRequirements(new PackingTrip.Weather(9, 25, Set.of(), null)))
                .containsExactlyInAnyOrder(
                        RecommendationWeather.COLD,
                        RecommendationWeather.MILD,
                        RecommendationWeather.HOT);
        var unknown =
                piece(
                        "Summer dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of("summer"),
                        List.of(),
                        List.of(),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var allWeather = piece("Recorded adaptable dress", GarmentCategory.DRESS, " ALL-WEATHER ");
        var coldOnly = piece("Cold dress", GarmentCategory.DRESS, "cold");
        var weather = new PackingTrip.Weather(5, 30, Set.of(), null);
        var result =
                rules.prepare(
                        trip(
                                1,
                                5,
                                0,
                                1,
                                weather,
                                Set.of(),
                                Set.of(),
                                List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                                List.of()),
                        List.of(unknown, allWeather, coldOnly));
        assertThat(result.problem().candidates())
                .extracting(candidate -> candidate.garment().id())
                .containsExactly(allWeather.id());
        assertThat(result.problem().requiresOuterwear()).isTrue();
    }

    @Test
    void onlyExplicitRainLayersQualifyAndALayerTopCannotReplaceABaseTop() {
        var jacket =
                piece(
                        "Waterproof jacket",
                        GarmentCategory.TOP,
                        " Blazer ",
                        List.of(),
                        List.of(),
                        List.of(" WATERPROOF "),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var unverified = piece("Unverified rain coat", GarmentCategory.OUTERWEAR);
        var shoes = piece("Shoes", GarmentCategory.SHOES);
        var bottom = piece("Bottom", GarmentCategory.BOTTOM);
        var rain = new PackingTrip.Weather(null, null, Set.of(RecommendationWeather.RAIN), null);
        var result =
                rules.prepare(
                        trip(
                                1,
                                5,
                                0,
                                1,
                                rain,
                                Set.of(),
                                Set.of(),
                                List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                                List.of()),
                        List.of(jacket, unverified, shoes, bottom));
        assertThat(result.problem().byId().get(jacket.id()).slot()).isEqualTo(Slot.OUTERWEAR);
        assertThat(result.problem().byId()).doesNotContainKey(unverified.id());
        assertThat(result.warnings())
                .anyMatch(
                        warning ->
                                warning.blocking()
                                        && warning.code().equals("MISSING_OUTFIT_STRUCTURE"));
        var top = piece("Base top", GarmentCategory.TOP);
        assertThat(
                        rules.prepare(result.problem().trip(), List.of(jacket, top, bottom, shoes))
                                .hasBlockingWarnings())
                .isFalse();
    }

    @Test
    void availabilityReviewExclusionsAndSeasonAreHardFilters() {
        var eligible =
                piece(
                        "Fall dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of(" FALL "),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var yearRound =
                piece(
                        "Year round shoes",
                        GarmentCategory.SHOES,
                        null,
                        List.of("year-round"),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var laundry =
                piece(
                        "Laundry",
                        GarmentCategory.DRESS,
                        null,
                        List.of("fall"),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.LAUNDRY,
                        ProcessingStatus.READY,
                        0);
        var packed =
                piece(
                        "Packed",
                        GarmentCategory.DRESS,
                        null,
                        List.of("fall"),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.PACKED,
                        ProcessingStatus.READY,
                        0);
        var archived =
                piece(
                        "Archived",
                        GarmentCategory.DRESS,
                        null,
                        List.of("fall"),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.ARCHIVED,
                        ProcessingStatus.READY,
                        0);
        var draft =
                piece(
                        "Draft",
                        GarmentCategory.DRESS,
                        null,
                        List.of("fall"),
                        List.of(),
                        List.of("mild"),
                        null,
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY_FOR_REVIEW,
                        0);
        var excluded = piece("Excluded", GarmentCategory.DRESS, "mild");
        var weather =
                new PackingTrip.Weather(
                        null, null, Set.of(RecommendationWeather.MILD), InsightSeason.AUTUMN);
        var result =
                rules.prepare(
                        trip(
                                1,
                                5,
                                0,
                                1,
                                weather,
                                Set.of(),
                                Set.of(excluded.id()),
                                List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                                List.of()),
                        List.of(eligible, yearRound, laundry, packed, archived, draft, excluded));
        assertThat(result.problem().byId()).containsOnlyKeys(eligible.id(), yearRound.id());
        assertThat(result.hasBlockingWarnings()).isFalse();
    }

    @Test
    void requiredConflictsAreExplainedWithoutPretendingThereIsASolution() {
        var required = piece("Required", GarmentCategory.DRESS, "mild");
        var unknown = piece("Unknown weather", GarmentCategory.TOP);
        var result =
                rules.prepare(
                        trip(
                                1,
                                1,
                                0,
                                1,
                                MILD,
                                Set.of(required.id(), unknown.id()),
                                Set.of(required.id()),
                                List.of(new PackingTrip.Occasion("Everyday", null, null, 1)),
                                List.of()),
                        List.of(required, unknown));
        assertThat(result.hasBlockingWarnings()).isTrue();
        assertThat(result.warnings())
                .extracting(warning -> warning.code())
                .contains(
                        "REQUIRED_EXCLUDED",
                        "REQUIRED_UNAVAILABLE",
                        "REQUIRED_OVER_MAXIMUM",
                        "MISSING_OUTFIT_STRUCTURE");
    }

    @Test
    void occasionAndFormalityUseRecordedTagsForEveryPiece() {
        var dress =
                piece(
                        "Formal dress",
                        GarmentCategory.DRESS,
                        null,
                        List.of(),
                        List.of(" EVENING "),
                        List.of("mild"),
                        "Formal",
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY,
                        0);
        var casual = piece("Casual shoes", GarmentCategory.SHOES, "mild");
        var trip =
                trip(
                        1,
                        5,
                        0,
                        1,
                        MILD,
                        Set.of(),
                        Set.of(),
                        List.of(new PackingTrip.Occasion("Dinner", "evening", "formal", 1)),
                        List.of());
        var result = rules.prepare(trip, List.of(dress, casual));
        assertThat(
                        result.problem()
                                .byId()
                                .get(dress.id())
                                .matches(result.problem().demands().getFirst()))
                .isTrue();
        assertThat(
                        result.problem()
                                .byId()
                                .get(casual.id())
                                .matches(result.problem().demands().getFirst()))
                .isFalse();
        assertThat(result.hasBlockingWarnings()).isTrue();
    }

    @Test
    void malformedWeatherAndInvalidNumericBoundsFailBeforeAnyWork() {
        for (var weather :
                List.of(
                        new PackingTrip.Weather(null, null, Set.of(), null),
                        new PackingTrip.Weather(10, null, Set.of(), null),
                        new PackingTrip.Weather(25, 5, Set.of(), null),
                        new PackingTrip.Weather(-51, 5, Set.of(), null),
                        new PackingTrip.Weather(5, 61, Set.of(), null)))
            assertThatThrownBy(
                            () ->
                                    rules.prepare(
                                            trip(
                                                    1,
                                                    5,
                                                    0,
                                                    1,
                                                    weather,
                                                    Set.of(),
                                                    Set.of(),
                                                    List.of(
                                                            new PackingTrip.Occasion(
                                                                    "Everyday", null, null, 1)),
                                                    List.of()),
                                            List.of()))
                    .isInstanceOf(DomainException.class);
        for (var trip :
                List.of(trip(1, 0, 0, 1), trip(1, 101, 0, 1), trip(1, 5, -1, 1), trip(1, 5, 0, 0)))
            assertThatThrownBy(() -> rules.prepare(trip, List.of()))
                    .isInstanceOf(DomainException.class);
    }

    @Test
    void beanValidationChecksNestedLabelsAndStrictFormalEventFields() {
        var trip =
                new PackingTrip(
                        " ",
                        START,
                        START,
                        "x".repeat(201),
                        new PackingTrip.Constraints(
                                5,
                                MILD,
                                List.of(new PackingTrip.Occasion(" ", null, null, 1)),
                                List.of(new PackingTrip.FormalEvent("Dinner", START, null, " ")),
                                0,
                                1,
                                Set.of(),
                                Set.of()));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(trip))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "name",
                            "locationText",
                            "constraints.occasions[0].name",
                            "constraints.formalEvents[0].formality");
        }
    }

    @Test
    void candidateOrderIsStableAndLargeWardrobesAreNeverSilentlyTruncated() {
        var a = piece("A", GarmentCategory.DRESS, "mild");
        var b = piece("B", GarmentCategory.SHOES, "mild");
        assertThat(rules.prepare(trip(1, 5, 0, 1), List.of(a, b)).problem().candidates())
                .isEqualTo(rules.prepare(trip(1, 5, 0, 1), List.of(b, a)).problem().candidates());
        var wardrobe =
                java.util.stream.IntStream.range(0, 2001)
                        .mapToObj(index -> piece("Piece " + index, GarmentCategory.DRESS, "mild"))
                        .toList();
        assertThatThrownBy(() -> rules.prepare(trip(1, 5, 0, 1), wardrobe))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("PACKING_LIMIT");
    }
}
