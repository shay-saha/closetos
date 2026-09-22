package com.closetos.search.api;

import com.closetos.garment.api.GarmentDetails;
import java.util.List;

public record SearchPage(
        List<Hit> items,
        String nextCursor,
        SearchMode mode,
        List<Constraint> appliedConstraints,
        int eligibleCount,
        int indexedCount) {
    public SearchPage {
        items = List.copyOf(items);
        appliedConstraints = List.copyOf(appliedConstraints);
    }

    public record Hit(GarmentDetails garment, List<String> explanations) {
        public Hit {
            explanations = List.copyOf(explanations);
        }
    }

    public record Constraint(String field, String operator, Object value, String explanation) {}
}
