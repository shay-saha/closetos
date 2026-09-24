package com.closetos.insights.api;

import com.closetos.garment.api.GarmentDetails;
import java.util.Locale;
import java.util.stream.Stream;

public record PairingContext(
        InsightSeason season, String formality, RecommendationWeather weather) {
    public PairingContext {
        formality = formality == null || formality.isBlank() ? null : formality.strip();
    }

    public boolean matches(GarmentDetails garment) {
        var metadata = garment.metadata();
        return (season == null || season.matches(metadata.seasonTags()))
                && (formality == null
                        || metadata.formality() != null
                                && formality
                                        .toLowerCase(Locale.ROOT)
                                        .equals(
                                                metadata.formality()
                                                        .strip()
                                                        .toLowerCase(Locale.ROOT)))
                && (weather == null
                        || Stream.of(
                                        metadata.seasonTags(),
                                        metadata.styleTags(),
                                        metadata.occasionTags())
                                .flatMap(java.util.Collection::stream)
                                .anyMatch(weather::matches));
    }
}
