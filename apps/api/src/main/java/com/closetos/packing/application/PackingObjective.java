package com.closetos.packing.application;

import com.closetos.packing.api.PackingProblem;
import com.closetos.packing.api.PackingProblem.Candidate;
import com.closetos.packing.api.PackingProblem.Demand;
import com.closetos.packing.api.PackingProblem.Slot;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PackingObjective {
    public static final int MAXIMUM_REWARDED_VARIETY = 20;

    private PackingObjective() {}

    public static List<Demand> contexts(PackingProblem problem) {
        var contexts = new LinkedHashMap<Context, Demand>();
        for (var demand : problem.demands())
            contexts.putIfAbsent(
                    new Context(
                            PackingRules.normalize(demand.occasionTag()),
                            PackingRules.normalize(demand.formality())),
                    demand);
        return List.copyOf(contexts.values());
    }

    public static long possibleOutfits(PackingProblem problem, Demand context, Set<UUID> selected) {
        var counts = new EnumMap<Slot, Long>(Slot.class);
        problem.candidates().stream()
                .filter(candidate -> selected.contains(candidate.garment().id()))
                .filter(candidate -> candidate.matches(context))
                .forEach(candidate -> counts.merge(candidate.slot(), 1L, Long::sum));
        long core =
                counts.getOrDefault(Slot.DRESS, 0L)
                        + counts.getOrDefault(Slot.TOP, 0L) * counts.getOrDefault(Slot.BOTTOM, 0L);
        long layers =
                counts.getOrDefault(Slot.OUTERWEAR, 0L) + (problem.requiresOuterwear() ? 0 : 1);
        return core * counts.getOrDefault(Slot.SHOES, 0L) * layers;
    }

    public static int versatility(PackingProblem problem, Candidate candidate) {
        if (candidate.slot() == Slot.EXTRA) return 0;
        return (int) contexts(problem).stream().filter(candidate::matches).count();
    }

    public static int underuse(Candidate candidate) {
        return (int) (100L / (Math.max(0, candidate.garment().wearCount()) + 1L));
    }

    public static String redundancyGroup(Candidate candidate) {
        var metadata = candidate.garment().metadata();
        if (candidate.slot() == Slot.EXTRA
                || metadata.subcategory() == null
                || metadata.primaryColourHex() == null
                || metadata.material() == null
                || metadata.pattern() == null) return "";
        return candidate.slot()
                + ":"
                + List.of(
                                metadata.subcategory(),
                                metadata.primaryColourHex(),
                                metadata.material(),
                                metadata.pattern(),
                                metadata.formality() == null ? "" : metadata.formality())
                        .stream()
                        .map(PackingRules::normalize)
                        .toList();
    }

    public static long cost(PackingProblem problem, Set<UUID> selected, Weights weights) {
        long cost = selected.size() * weights.itemCount();
        int contextCount = contexts(problem).size();
        long varietyWeight = weights.outfitVariety() / Math.max(1, contextCount);
        for (var context : contexts(problem))
            cost -=
                    varietyWeight
                            * Math.min(
                                    MAXIMUM_REWARDED_VARIETY,
                                    possibleOutfits(problem, context, selected));
        var redundant = new java.util.HashMap<String, Integer>();
        for (var candidate : problem.candidates()) {
            if (!selected.contains(candidate.garment().id())) continue;
            cost -= weights.versatility() * versatility(problem, candidate);
            cost -= weights.underuse() * underuse(candidate);
            String group = redundancyGroup(candidate);
            if (!group.isEmpty()) redundant.merge(group, 1, Integer::sum);
        }
        for (int count : redundant.values()) cost += weights.redundancy() * Math.max(0, count - 1);
        return cost;
    }

    public record Weights(
            @org.springframework.boot.context.properties.bind.DefaultValue("10000") long itemCount,
            @org.springframework.boot.context.properties.bind.DefaultValue("1000")
                    long outfitVariety,
            @org.springframework.boot.context.properties.bind.DefaultValue("50") long versatility,
            @org.springframework.boot.context.properties.bind.DefaultValue("5") long underuse,
            @org.springframework.boot.context.properties.bind.DefaultValue("300") long redundancy) {
        public Weights {
            if (itemCount < 1
                    || outfitVariety < 0
                    || versatility < 0
                    || underuse < 0
                    || redundancy < 0
                    || itemCount > 1_000_000
                    || outfitVariety > 1_000_000
                    || versatility > 1_000_000
                    || underuse > 1_000_000
                    || redundancy > 1_000_000)
                throw new IllegalArgumentException(
                        "Packing weights must be bounded non-negative integers with a positive item-count weight.");
        }

        public static Weights defaults() {
            return new Weights(10_000, 1_000, 50, 5, 300);
        }
    }

    private record Context(String occasionTag, String formality) {}
}
