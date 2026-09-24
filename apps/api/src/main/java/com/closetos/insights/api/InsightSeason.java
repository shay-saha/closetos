package com.closetos.insights.api;

import java.util.List;
import java.util.Locale;

public enum InsightSeason {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER;

    public boolean matches(List<String> tags) {
        return tags.stream()
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                .anyMatch(
                        tag ->
                                tag.equals(name().toLowerCase(Locale.ROOT))
                                        || this == AUTUMN && tag.equals("fall")
                                        || List.of(
                                                        "all",
                                                        "all-season",
                                                        "all seasons",
                                                        "all-seasons",
                                                        "year-round")
                                                .contains(tag));
    }
}
