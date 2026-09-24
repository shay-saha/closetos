package com.closetos.packing.application;

import com.closetos.packing.api.PackingProblem;
import com.closetos.packing.api.PackingSolution.ConstraintWarning;
import java.util.List;

public record PackingPreparation(PackingProblem problem, List<ConstraintWarning> warnings) {
    public PackingPreparation {
        warnings = List.copyOf(warnings);
    }

    public boolean hasBlockingWarnings() {
        return warnings.stream().anyMatch(ConstraintWarning::blocking);
    }
}
