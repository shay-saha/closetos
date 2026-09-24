package com.closetos.packing.application;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentMetadata;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.RecommendationWeather;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingTrip;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

final class PackingFixtures {
    static final LocalDate START = LocalDate.parse("2026-07-01");
    static final PackingTrip.Weather MILD =
            new PackingTrip.Weather(null, null, Set.of(RecommendationWeather.MILD), null);

    private PackingFixtures() {}

    static GarmentDetails piece(String name, GarmentCategory category, String... weatherTags) {
        return piece(
                name,
                category,
                null,
                List.of(),
                List.of(),
                List.of(weatherTags),
                null,
                GarmentStatus.AVAILABLE,
                ProcessingStatus.READY,
                0);
    }

    static GarmentDetails piece(
            String name,
            GarmentCategory category,
            String subtype,
            List<String> seasons,
            List<String> occasions,
            List<String> styles,
            String formality,
            GarmentStatus status,
            ProcessingStatus processing,
            int wears) {
        var metadata =
                new GarmentMetadata(
                        name, category, subtype, null, null, "Black", "#000000", List.of(), "Solid",
                        "Cotton", null, formality, seasons, occasions, styles, null, null, null,
                        null);
        return new GarmentDetails(
                UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
                metadata,
                status,
                processing,
                wears,
                null,
                null,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null);
    }

    static PackingTrip trip(int days, int maximum, int laundry, int rewear) {
        return trip(
                days,
                maximum,
                laundry,
                rewear,
                MILD,
                Set.of(),
                Set.of(),
                List.of(new PackingTrip.Occasion("Everyday", null, null, days)),
                List.of());
    }

    static PackingTrip trip(
            int days,
            int maximum,
            int laundry,
            int rewear,
            PackingTrip.Weather weather,
            Set<UUID> required,
            Set<UUID> excluded,
            List<PackingTrip.Occasion> occasions,
            List<PackingTrip.FormalEvent> events) {
        return new PackingTrip(
                "July trip",
                START,
                START.plusDays(days - 1L),
                null,
                new PackingTrip.Constraints(
                        maximum, weather, occasions, events, laundry, rewear, required, excluded));
    }

    static PackingSolution solution(
            List<GarmentDetails> selected, List<List<GarmentDetails>> outfits) {
        return new PackingSolution(
                PackingSolution.Status.OPTIMAL,
                selected.stream().map(GarmentDetails::id).toList(),
                IntStream.range(0, outfits.size())
                        .mapToObj(
                                index ->
                                        new PackingSolution.ScheduledOutfit(
                                                index,
                                                outfits.get(index).stream()
                                                        .map(GarmentDetails::id)
                                                        .toList()))
                        .toList(),
                List.of());
    }
}
