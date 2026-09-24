package com.closetos.packing.application;

import com.closetos.packing.api.PackingProblem;
import com.closetos.packing.api.PackingProblem.Slot;
import com.closetos.packing.api.PackingSolution;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PackingVerifier {
    public void verify(PackingProblem problem, PackingSolution solution) {
        if (!solution.hasSolution()) {
            require(
                    solution.selectedGarments().isEmpty() && solution.outfits().isEmpty(),
                    "An unsolved request must not contain a capsule.");
            return;
        }
        require(
                solution.warnings().stream().noneMatch(warning -> warning.blocking()),
                "A successful capsule cannot contain blocking warnings.");
        var selected = Set.copyOf(solution.selectedGarments());
        require(
                selected.size() == solution.selectedGarments().size(),
                "Selected pieces must be unique.");
        var constraints = problem.trip().constraints();
        require(
                selected.size() <= constraints.maximumGarments(),
                "Maximum garment count was exceeded.");
        require(
                selected.containsAll(constraints.requiredGarments()),
                "A required piece is missing.");
        require(
                selected.stream().noneMatch(constraints.excludedGarments()::contains),
                "An excluded piece was selected.");
        var candidates = problem.byId();
        require(
                candidates.keySet().containsAll(selected),
                "Selected pieces must be reviewed, available, and compatible with the trip weather and season.");
        require(
                solution.outfits().size() == problem.demands().size(),
                "Every scheduled day and formal event needs an outfit.");
        var seen = new HashSet<Integer>();
        var wear = new HashMap<WearAllowance, Integer>();
        for (var outfit : solution.outfits()) {
            int index = outfit.demandIndex();
            require(
                    index >= 0 && index < problem.demands().size() && seen.add(index),
                    "Outfits must identify every scheduled demand exactly once.");
            var demand = problem.demands().get(index);
            require(demand.index() == index, "Demand indices must be consecutive.");
            require(
                    selected.containsAll(outfit.garmentIds()),
                    "An outfit contains a piece outside the capsule.");
            require(
                    new HashSet<>(outfit.garmentIds()).size() == outfit.garmentIds().size(),
                    "An outfit repeats the same piece.");
            var slots = new EnumMap<Slot, Integer>(Slot.class);
            for (UUID id : outfit.garmentIds()) {
                var candidate = candidates.get(id);
                require(
                        candidate.matches(demand),
                        "An outfit does not match its occasion or formality.");
                slots.merge(candidate.slot(), 1, Integer::sum);
                if (candidate.slot().usesRewearAllowance())
                    wear.merge(new WearAllowance(id, demand.laundryPeriod()), 1, Integer::sum);
            }
            require(
                    slots.getOrDefault(Slot.SHOES, 0) == 1,
                    "An outfit needs exactly one pair of shoes.");
            int dress = slots.getOrDefault(Slot.DRESS, 0);
            int top = slots.getOrDefault(Slot.TOP, 0);
            int bottom = slots.getOrDefault(Slot.BOTTOM, 0);
            require(
                    dress == 1 && top == 0 && bottom == 0 || dress == 0 && top == 1 && bottom == 1,
                    "An outfit needs one dress or one top and one bottom.");
            int outerwear = slots.getOrDefault(Slot.OUTERWEAR, 0);
            require(
                    outerwear <= 1 && (!problem.requiresOuterwear() || outerwear == 1),
                    "Cold or rainy trips need a compatible outer layer in every outfit.");
            require(
                    slots.getOrDefault(Slot.EXTRA, 0) == 0,
                    "Accessory items belong in the packing list; the solver's outfits contain clothing and footwear.");
        }
        require(
                wear.values().stream()
                        .allMatch(count -> count <= constraints.maximumWearsBetweenLaundry()),
                "The clothing rewear allowance was exceeded before the next laundry opportunity.");
    }

    public List<String> explanations(PackingProblem problem, PackingSolution solution) {
        return explanations(problem, solution, false);
    }

    public List<String> explanations(
            PackingProblem problem, PackingSolution solution, boolean manual) {
        verify(problem, solution);
        if (!solution.hasSolution()) return List.of();
        var constraints = problem.trip().constraints();
        return List.of(
                "Selected "
                        + solution.selectedGarments().size()
                        + " unique pieces within the maximum of "
                        + constraints.maximumGarments()
                        + ".",
                "Covered all "
                        + problem.demands().size()
                        + " scheduled days and formal events with complete outfits.",
                "Occasion days are scheduled consecutively in the entered order. Formal events add an outfit on their event date.",
                "Only reviewed AVAILABLE pieces that meet the requested recorded tags were considered. Missing compatibility was not inferred.",
                "Temperature bands are cold below 10°C, mild from 10°C through 24°C, and hot above 24°C. Clothing and footwear must have matching weather tags for every band in the range, or an explicit all-weather tag.",
                "Rain requires an outer layer with an explicit rain, rainy, wet-weather, or waterproof tag. Accessory weather protection is not inferred.",
                "Tops, bottoms, and dresses may appear in at most "
                        + constraints.maximumWearsBetweenLaundry()
                        + " scheduled outfits per laundry period. Shoes and outer layers may be reused without this limit.",
                constraints.laundryEveryDays() == 0
                        ? "No laundry is assumed during this trip."
                        : "Laundry is assumed complete before the next day's outfit after every "
                                + constraints.laundryEveryDays()
                                + " trip days.",
                manual
                        ? "This manual capsule was checked against every hard constraint. Optimality is not claimed."
                        : solution.status() == PackingSolution.Status.OPTIMAL
                                ? "The solver proved this capsule optimal for the configured objective."
                                : "This capsule satisfies all hard constraints; the solver did not prove optimality within its limit.");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new InvalidPackingSolution(message);
    }

    private record WearAllowance(UUID garmentId, int laundryPeriod) {}

    public static final class InvalidPackingSolution extends IllegalStateException {
        public InvalidPackingSolution(String message) {
            super(message);
        }
    }
}
