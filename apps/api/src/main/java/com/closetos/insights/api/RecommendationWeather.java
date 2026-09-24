package com.closetos.insights.api;

import java.util.Set;

public enum RecommendationWeather {
    COLD(Set.of("cold", "cold-weather")),
    MILD(Set.of("mild", "mild-weather")),
    HOT(Set.of("hot", "hot-weather")),
    RAIN(Set.of("rain", "rainy", "wet-weather", "waterproof"));

    private final Set<String> tags;

    RecommendationWeather(Set<String> tags) {
        this.tags = tags;
    }

    public boolean matches(String tag) {
        return tags.contains(tag.strip().toLowerCase(java.util.Locale.ROOT));
    }
}
