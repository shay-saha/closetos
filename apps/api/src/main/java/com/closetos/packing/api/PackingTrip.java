package com.closetos.packing.api;

import com.closetos.insights.api.InsightSeason;
import com.closetos.insights.api.RecommendationWeather;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PackingTrip(
        @NotBlank @Size(max = 160) String name,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Size(max = 200) String locationText,
        @NotNull @Valid Constraints constraints) {
    public PackingTrip {
        name = clean(name);
        locationText = clean(locationText);
    }

    public record Constraints(
            @Min(1) @Max(100) int maximumGarments,
            @NotNull @Valid Weather weather,
            @NotNull @Size(min = 1, max = 8) List<@NotNull @Valid Occasion> occasions,
            @Size(max = 16) List<@NotNull @Valid FormalEvent> formalEvents,
            @Min(0) @Max(31) int laundryEveryDays,
            @Min(1) @Max(31) int maximumWearsBetweenLaundry,
            @Size(max = 100) Set<@NotNull UUID> requiredGarments,
            @Size(max = 2000) Set<@NotNull UUID> excludedGarments) {
        public Constraints {
            occasions = occasions == null ? List.of() : List.copyOf(occasions);
            formalEvents = formalEvents == null ? List.of() : List.copyOf(formalEvents);
            requiredGarments = requiredGarments == null ? Set.of() : Set.copyOf(requiredGarments);
            excludedGarments = excludedGarments == null ? Set.of() : Set.copyOf(excludedGarments);
        }
    }

    public record Weather(
            @Min(-50) @Max(60) Integer minimumTemperatureC,
            @Min(-50) @Max(60) Integer maximumTemperatureC,
            Set<@NotNull RecommendationWeather> assumptions,
            InsightSeason season) {
        public Weather {
            assumptions = assumptions == null ? Set.of() : Set.copyOf(assumptions);
        }
    }

    public record Occasion(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 60) String occasionTag,
            @Size(max = 60) String formality,
            @Min(1) @Max(31) int days) {
        public Occasion {
            name = clean(name);
            occasionTag = clean(occasionTag);
            formality = clean(formality);
        }
    }

    public record FormalEvent(
            @NotBlank @Size(max = 80) String name,
            @NotNull LocalDate date,
            @Size(max = 60) String occasionTag,
            @NotBlank @Size(max = 60) String formality) {
        public FormalEvent {
            name = clean(name);
            occasionTag = clean(occasionTag);
            formality = clean(formality);
        }
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
