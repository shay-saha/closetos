package com.closetos.packing.application;

import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.RecommendationWeather;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.packing.api.PackingProblem;
import com.closetos.packing.api.PackingProblem.Candidate;
import com.closetos.packing.api.PackingProblem.Demand;
import com.closetos.packing.api.PackingProblem.Slot;
import com.closetos.packing.api.PackingSolution.ConstraintWarning;
import com.closetos.packing.api.PackingTrip;
import com.closetos.platform.api.DomainException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class PackingRules {
    private static final int MAXIMUM_CANDIDATES = 2000;
    private static final Set<String> LAYER_TOPS =
            Set.of("cardigan", "jacket", "blazer", "overshirt", "shirt-jacket");

    public PackingPreparation prepare(PackingTrip trip, List<GarmentDetails> ownedGarments) {
        validate(trip);
        var constraints = trip.constraints();
        var warnings = new ArrayList<ConstraintWarning>();
        var requirements = thermalRequirements(constraints.weather());
        boolean outerwear =
                requirements.contains(RecommendationWeather.COLD)
                        || constraints.weather().assumptions().contains(RecommendationWeather.RAIN);
        var available =
                ownedGarments.stream()
                        .filter(piece -> piece.status() == GarmentStatus.AVAILABLE)
                        .filter(piece -> piece.processingStatus() == ProcessingStatus.READY)
                        .filter(piece -> !constraints.excludedGarments().contains(piece.id()))
                        .toList();
        var compatible =
                available.stream()
                        .filter(
                                piece ->
                                        compatibleWeather(
                                                piece, constraints.weather(), requirements))
                        .sorted(Comparator.comparing(piece -> piece.id().toString()))
                        .map(piece -> new Candidate(piece, slot(piece)))
                        .toList();
        if (compatible.size() > MAXIMUM_CANDIDATES)
            throw new DomainException(
                    422,
                    "PACKING_LIMIT",
                    "More than 2,000 eligible pieces match this trip. Add exclusions or narrower weather and season constraints.");
        var ids =
                compatible.stream().map(piece -> piece.garment().id()).collect(Collectors.toSet());
        var overlap =
                constraints.requiredGarments().stream()
                        .filter(constraints.excludedGarments()::contains)
                        .count();
        if (overlap > 0)
            warnings.add(
                    new ConstraintWarning(
                            "REQUIRED_EXCLUDED",
                            overlap
                                    + " required pieces are also excluded. Remove the conflicting constraint.",
                            true));
        var missing =
                constraints.requiredGarments().stream()
                        .filter(
                                id ->
                                        !ids.contains(id)
                                                && !constraints.excludedGarments().contains(id))
                        .count();
        if (missing > 0)
            warnings.add(
                    new ConstraintWarning(
                            "REQUIRED_UNAVAILABLE",
                            missing
                                    + " required pieces are unavailable, awaiting review, or do not match the weather and season tags.",
                            true));
        if (constraints.requiredGarments().size() > constraints.maximumGarments())
            warnings.add(
                    new ConstraintWarning(
                            "REQUIRED_OVER_MAXIMUM",
                            "The required pieces alone exceed the maximum garment count.",
                            true));
        if (available.size() > compatible.size())
            warnings.add(
                    new ConstraintWarning(
                            "WEATHER_EXCLUSIONS",
                            (available.size() - compatible.size())
                                    + " available pieces were excluded because their recorded weather or season tags do not establish compatibility.",
                            false));
        var demands = demands(trip);
        for (var demand : demands) {
            var slots =
                    compatible.stream()
                            .filter(piece -> piece.matches(demand))
                            .map(Candidate::slot)
                            .collect(Collectors.toSet());
            if (!slots.contains(Slot.SHOES)
                    || !(slots.contains(Slot.DRESS)
                            || slots.contains(Slot.TOP) && slots.contains(Slot.BOTTOM))
                    || outerwear && !slots.contains(Slot.OUTERWEAR))
                warnings.add(
                        new ConstraintWarning(
                                "MISSING_OUTFIT_STRUCTURE",
                                "No complete outfit can cover "
                                        + demand.name()
                                        + " on "
                                        + demand.date()
                                        + ". Each outfit needs a dress or a top and bottom, shoes"
                                        + (outerwear ? ", and a compatible outer layer." : "."),
                                true));
        }
        return new PackingPreparation(
                new PackingProblem(trip, compatible, demands, outerwear), warnings);
    }

    public static void validate(PackingTrip trip) {
        if (trip == null
                || trip.startDate() == null
                || trip.endDate() == null
                || trip.constraints() == null)
            throw DomainException.invalid("Trip dates and constraints are required.");
        long days = ChronoUnit.DAYS.between(trip.startDate(), trip.endDate()) + 1;
        if (days < 1 || days > 31)
            throw DomainException.invalid(
                    "A trip must last between 1 and 31 days, including both dates.");
        var constraints = trip.constraints();
        if (constraints.maximumGarments() < 1
                || constraints.maximumGarments() > 100
                || constraints.maximumWearsBetweenLaundry() < 1
                || constraints.maximumWearsBetweenLaundry() > 31
                || constraints.laundryEveryDays() < 0
                || constraints.laundryEveryDays() > 31)
            throw DomainException.invalid(
                    "Garment count, laundry interval, and rewear allowance are outside their supported ranges.");
        if (constraints.occasions().isEmpty()
                || constraints.occasions().size() > 8
                || constraints.occasions().stream()
                        .anyMatch(occasion -> occasion.days() < 1 || occasion.days() > 31)
                || constraints.occasions().stream().mapToLong(PackingTrip.Occasion::days).sum()
                        != days)
            throw DomainException.invalid(
                    "Occasion days must cover every trip day exactly once. They are scheduled in the entered order.");
        if (constraints.formalEvents().size() > 16
                || constraints.formalEvents().stream()
                        .anyMatch(
                                event ->
                                        event.date() == null
                                                || event.date().isBefore(trip.startDate())
                                                || event.date().isAfter(trip.endDate())
                                                || event.formality() == null))
            throw DomainException.invalid(
                    "Every formal event needs a formality and a date within the trip.");
        if (constraints.requiredGarments().size() > 100
                || constraints.excludedGarments().size() > 2000)
            throw DomainException.invalid(
                    "A trip supports up to 100 required and 2,000 excluded pieces.");
        var weather = constraints.weather();
        if (weather == null
                || (weather.minimumTemperatureC() == null)
                        != (weather.maximumTemperatureC() == null))
            throw DomainException.invalid(
                    "Provide both temperature bounds, or use weather assumptions.");
        if (weather.minimumTemperatureC() == null && weather.assumptions().isEmpty())
            throw DomainException.invalid(
                    "A temperature range or at least one weather assumption is required.");
        if (weather.minimumTemperatureC() != null
                && (weather.minimumTemperatureC() < -50
                        || weather.maximumTemperatureC() > 60
                        || weather.minimumTemperatureC() > weather.maximumTemperatureC()))
            throw DomainException.invalid(
                    "Temperature bounds must be ordered and between -50 and 60 degrees Celsius.");
    }

    public static Slot slot(GarmentDetails piece) {
        return switch (piece.metadata().category()) {
            case TOP ->
                    LAYER_TOPS.contains(normalize(piece.metadata().subcategory()))
                            ? Slot.OUTERWEAR
                            : Slot.TOP;
            case BOTTOM -> Slot.BOTTOM;
            case DRESS -> Slot.DRESS;
            case SHOES -> Slot.SHOES;
            case OUTERWEAR -> Slot.OUTERWEAR;
            default -> Slot.EXTRA;
        };
    }

    public static Set<RecommendationWeather> thermalRequirements(PackingTrip.Weather weather) {
        var requirements = EnumSet.noneOf(RecommendationWeather.class);
        requirements.addAll(weather.assumptions());
        requirements.remove(RecommendationWeather.RAIN);
        if (weather.minimumTemperatureC() != null) {
            if (weather.minimumTemperatureC() < 10) requirements.add(RecommendationWeather.COLD);
            if (weather.maximumTemperatureC() >= 10 && weather.minimumTemperatureC() <= 24)
                requirements.add(RecommendationWeather.MILD);
            if (weather.maximumTemperatureC() > 24) requirements.add(RecommendationWeather.HOT);
        }
        return Set.copyOf(requirements);
    }

    private boolean compatibleWeather(
            GarmentDetails piece, PackingTrip.Weather weather, Set<RecommendationWeather> thermal) {
        var metadata = piece.metadata();
        if (weather.season() != null && !weather.season().matches(metadata.seasonTags()))
            return false;
        var slot = slot(piece);
        if (slot == Slot.EXTRA) return true;
        var tags =
                Stream.of(metadata.seasonTags(), metadata.occasionTags(), metadata.styleTags())
                        .flatMap(List::stream)
                        .map(PackingRules::normalize)
                        .collect(Collectors.toSet());
        if (!thermal.isEmpty()
                && !tags.contains("all-weather")
                && !thermal.stream()
                        .allMatch(requirement -> tags.stream().anyMatch(requirement::matches)))
            return false;
        return slot != Slot.OUTERWEAR
                || !weather.assumptions().contains(RecommendationWeather.RAIN)
                || tags.stream().anyMatch(RecommendationWeather.RAIN::matches);
    }

    List<Demand> demands(PackingTrip trip) {
        var demands = new ArrayList<Demand>();
        int offset = 0;
        for (var occasion : trip.constraints().occasions())
            for (int day = 0; day < occasion.days(); day++) {
                demands.add(
                        new Demand(
                                demands.size(),
                                trip.startDate().plusDays(offset),
                                occasion.name(),
                                occasion.occasionTag(),
                                occasion.formality(),
                                laundryPeriod(trip, offset)));
                offset++;
            }
        for (var event : trip.constraints().formalEvents()) {
            int day = (int) ChronoUnit.DAYS.between(trip.startDate(), event.date());
            demands.add(
                    new Demand(
                            demands.size(),
                            event.date(),
                            event.name(),
                            event.occasionTag(),
                            event.formality(),
                            laundryPeriod(trip, day)));
        }
        return List.copyOf(demands);
    }

    private int laundryPeriod(PackingTrip trip, int day) {
        return trip.constraints().laundryEveryDays() == 0
                ? 0
                : day / trip.constraints().laundryEveryDays();
    }

    public static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }
}
