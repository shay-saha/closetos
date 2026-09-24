package com.closetos.packing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record PackingSolution(
        @NotNull Status status,
        @NotNull @Size(max = 100) List<@NotNull UUID> selectedGarments,
        @NotNull @Size(max = 47) List<@NotNull @Valid ScheduledOutfit> outfits,
        @NotNull @Size(max = 64) List<@NotNull @Valid ConstraintWarning> warnings) {
    public PackingSolution {
        selectedGarments = List.copyOf(selectedGarments);
        outfits = List.copyOf(outfits);
        warnings = List.copyOf(warnings);
    }

    public boolean hasSolution() {
        return status == Status.OPTIMAL || status == Status.FEASIBLE;
    }

    public enum Status {
        OPTIMAL,
        FEASIBLE,
        INFEASIBLE,
        TIME_LIMIT
    }

    public record ScheduledOutfit(
            @Min(0) @Max(46) int demandIndex,
            @NotNull @Size(max = 4) List<@NotNull UUID> garmentIds) {
        public ScheduledOutfit {
            garmentIds = List.copyOf(garmentIds);
        }
    }

    public record ConstraintWarning(
            @NotNull @Size(max = 80) String code,
            @NotNull @Size(max = 600) String detail,
            boolean blocking) {}
}
