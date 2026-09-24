package com.closetos.packing.application;

import com.closetos.packing.api.PackingDetails;
import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingSolverPort;
import com.closetos.platform.api.DomainException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PackingOptimisation {
    private final PackingLists lists;
    private final Optional<PackingSolverPort> solver;
    private final PackingVerifier verifier;

    public PackingOptimisation(
            PackingLists lists, Optional<PackingSolverPort> solver, PackingVerifier verifier) {
        this.lists = lists;
        this.solver = solver;
        this.verifier = verifier;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PackingDetails optimise(UUID id, long version) {
        var snapshot = lists.snapshot(id, version);
        var preparation = snapshot.preparation();
        PackingSolution result;
        if (preparation.hasBlockingWarnings()) {
            result =
                    new PackingSolution(
                            PackingSolution.Status.INFEASIBLE,
                            List.of(),
                            List.of(),
                            preparation.warnings());
        } else {
            var nativeSolver =
                    solver.orElseThrow(
                            () ->
                                    new DomainException(
                                            503,
                                            "PACKING_SOLVER_UNAVAILABLE",
                                            "The packing solver is unavailable. Your saved list has not changed. Try again when the solver is ready."));
            result = nativeSolver.solve(preparation.problem());
            var warnings = new java.util.ArrayList<>(preparation.warnings());
            warnings.addAll(result.warnings());
            result =
                    new PackingSolution(
                            result.status(), result.selectedGarments(), result.outfits(), warnings);
        }
        verifier.verify(preparation.problem(), result);
        return lists.saveSolution(snapshot, result, false);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PackingDetails override(UUID id, long version, PackingSolution submitted) {
        var snapshot = lists.snapshot(id, version);
        var preparation = snapshot.preparation();
        if (preparation.hasBlockingWarnings())
            throw new DomainException(
                    422,
                    "PACKING_CONSTRAINTS",
                    "Resolve the trip's blocking constraints before saving a manual capsule.");
        var checked =
                new PackingSolution(
                        PackingSolution.Status.FEASIBLE,
                        submitted.selectedGarments(),
                        submitted.outfits(),
                        preparation.warnings());
        try {
            verifier.verify(preparation.problem(), checked);
        } catch (PackingVerifier.InvalidPackingSolution invalid) {
            throw DomainException.invalid(invalid.getMessage());
        }
        return lists.saveSolution(snapshot, checked, true);
    }
}
