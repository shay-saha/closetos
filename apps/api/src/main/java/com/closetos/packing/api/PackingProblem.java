package com.closetos.packing.api;

import com.closetos.garment.api.GarmentDetails;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record PackingProblem(
        PackingTrip trip,
        List<Candidate> candidates,
        List<Demand> demands,
        boolean requiresOuterwear) {
    public PackingProblem {
        candidates = List.copyOf(candidates);
        demands = List.copyOf(demands);
    }

    public Map<UUID, Candidate> byId() {
        return candidates.stream()
                .collect(
                        Collectors.toMap(
                                candidate -> candidate.garment().id(), Function.identity()));
    }

    public record Candidate(GarmentDetails garment, Slot slot) {
        public boolean matches(Demand demand) {
            var metadata = garment.metadata();
            return (demand.occasionTag() == null
                            || metadata.occasionTags().stream()
                                    .anyMatch(
                                            tag ->
                                                    tag.strip()
                                                            .equalsIgnoreCase(
                                                                    demand.occasionTag())))
                    && (demand.formality() == null
                            || metadata.formality() != null
                                    && demand.formality()
                                            .equalsIgnoreCase(metadata.formality().strip()));
        }
    }

    public record Demand(
            int index,
            LocalDate date,
            String name,
            String occasionTag,
            String formality,
            int laundryPeriod) {}

    public enum Slot {
        TOP,
        BOTTOM,
        DRESS,
        SHOES,
        OUTERWEAR,
        EXTRA;

        public boolean usesRewearAllowance() {
            return this == TOP || this == BOTTOM || this == DRESS;
        }
    }
}
