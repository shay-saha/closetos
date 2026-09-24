package com.closetos.packing.infrastructure;

import com.closetos.packing.api.PackingProblem;
import com.closetos.packing.api.PackingProblem.Slot;
import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingSolution.ConstraintWarning;
import com.closetos.packing.api.PackingSolution.ScheduledOutfit;
import com.closetos.packing.api.PackingSolution.Status;
import com.closetos.packing.api.PackingSolverPort;
import com.closetos.packing.application.PackingObjective;
import com.closetos.packing.application.PackingRules;
import com.closetos.packing.application.PackingVerifier;
import com.closetos.platform.api.DomainException;
import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OrToolsPackingSolver implements PackingSolverPort {
    private static final Logger LOG = LoggerFactory.getLogger(OrToolsPackingSolver.class);
    private final PackingSolverSettings settings;
    private final PackingVerifier verifier;
    private final Semaphore capacity;

    public OrToolsPackingSolver(PackingSolverSettings settings, PackingVerifier verifier) {
        this.settings = settings;
        this.verifier = verifier;
        this.capacity = new Semaphore(settings.maximumConcurrent());
    }

    @Override
    public PackingSolution solve(PackingProblem problem) {
        if (!capacity.tryAcquire())
            throw new DomainException(
                    429,
                    "PACKING_CAPACITY",
                    "The packing solver is busy. Try again shortly; your saved list has not changed.");
        try {
            loadLibraries();
            PackingRules.validate(problem.trip());
            var formulation = formulate(problem);
            String invalid = formulation.model().validate();
            if (!invalid.isEmpty())
                throw new IllegalStateException("The packing model is invalid: " + invalid);
            var solver = new CpSolver();
            solver.getParameters()
                    .setNumSearchWorkers(1)
                    .setRandomSeed(0)
                    .setMaxTimeInSeconds(settings.maximumSeconds())
                    .setMaxDeterministicTime(settings.maximumDeterministicTime())
                    .setLogSearchProgress(false);
            var status = solver.solve(formulation.model());
            var result = result(problem, formulation, solver, status);
            verifier.verify(problem, result);
            return result;
        } finally {
            capacity.release();
        }
    }

    private void loadLibraries() {
        try {
            Loader.loadNativeLibraries();
        } catch (LinkageError | RuntimeException failure) {
            LOG.error("The native packing solver could not load", failure);
            throw new DomainException(
                    503,
                    "PACKING_SOLVER_UNAVAILABLE",
                    "The packing solver could not start. Your saved list has not changed. Try again when the solver is ready.");
        }
    }

    private Formulation formulate(PackingProblem problem) {
        var model = new CpModel();
        var pieces = new LinkedHashMap<UUID, BoolVar>();
        var assignments = new ArrayList<Map<UUID, BoolVar>>();
        var rewear = new LinkedHashMap<WearPeriod, List<BoolVar>>();
        var labels = new HashMap<Integer, ConstraintWarning>();
        for (var candidate : problem.candidates()) {
            UUID id = candidate.garment().id();
            var selected = model.newBoolVar("piece_" + id);
            pieces.put(id, selected);
            if (problem.trip().constraints().excludedGarments().contains(id))
                model.addEquality(selected, 0);
            if (problem.trip().constraints().requiredGarments().contains(id)) {
                var required =
                        assumption(
                                model,
                                labels,
                                "required_" + id,
                                "REQUIRED_PIECE",
                                "Include the required piece “"
                                        + candidate.garment().metadata().name()
                                        + "”.");
                model.addEquality(selected, 1).onlyEnforceIf(required);
            }
        }
        if (!pieces.keySet().containsAll(problem.trip().constraints().requiredGarments()))
            model.addEquality(model.newConstant(0), 1);
        var maximum =
                assumption(
                        model,
                        labels,
                        "maximum_items",
                        "MAXIMUM_GARMENTS",
                        "Pack at most "
                                + problem.trip().constraints().maximumGarments()
                                + " unique pieces.");
        model.addLessOrEqual(sum(pieces.values()), problem.trip().constraints().maximumGarments())
                .onlyEnforceIf(maximum);
        for (var demand : problem.demands()) {
            var selected = new LinkedHashMap<UUID, BoolVar>();
            var slots = new EnumMap<Slot, List<BoolVar>>(Slot.class);
            for (var slot : Slot.values()) slots.put(slot, new ArrayList<>());
            for (var candidate : problem.candidates()) {
                if (candidate.slot() == Slot.EXTRA || !candidate.matches(demand)) continue;
                UUID id = candidate.garment().id();
                var assigned = model.newBoolVar("wear_" + demand.index() + "_" + id);
                selected.put(id, assigned);
                slots.get(candidate.slot()).add(assigned);
                model.addImplication(assigned, pieces.get(id));
                if (candidate.slot().usesRewearAllowance())
                    rewear.computeIfAbsent(
                                    new WearPeriod(id, demand.laundryPeriod()),
                                    ignored -> new ArrayList<>())
                            .add(assigned);
            }
            assignments.add(selected);
            var occasion =
                    assumption(
                            model,
                            labels,
                            "occasion_" + demand.index(),
                            "REQUIRED_OCCASION",
                            "Cover "
                                    + demand.name()
                                    + " on "
                                    + demand.date()
                                    + " with a complete outfit matching its recorded occasion and formality.");
            var dress = model.newBoolVar("dress_outfit_" + demand.index());
            model.addEquality(sum(slots.get(Slot.DRESS)), dress).onlyEnforceIf(occasion);
            model.addEquality(sum(slots.get(Slot.TOP)).add(dress), 1).onlyEnforceIf(occasion);
            model.addEquality(sum(slots.get(Slot.BOTTOM)).add(dress), 1).onlyEnforceIf(occasion);
            model.addEquality(sum(slots.get(Slot.SHOES)), 1).onlyEnforceIf(occasion);
            model.addLessOrEqual(sum(slots.get(Slot.OUTERWEAR)), 1);
            if (problem.requiresOuterwear())
                model.addEquality(sum(slots.get(Slot.OUTERWEAR)), 1).onlyEnforceIf(occasion);
        }
        var allowances = new LinkedHashMap<Integer, BoolVar>();
        for (var group : rewear.entrySet()) {
            int period = group.getKey().period();
            var allowance =
                    allowances.computeIfAbsent(
                            period,
                            ignored ->
                                    assumption(
                                            model,
                                            labels,
                                            "rewear_" + period,
                                            "REWEAR_ALLOWANCE",
                                            "Use each top, bottom, or dress at most "
                                                    + problem.trip()
                                                            .constraints()
                                                            .maximumWearsBetweenLaundry()
                                                    + " times in laundry period "
                                                    + (period + 1)
                                                    + "."));
            model.addLessOrEqual(
                            sum(group.getValue()),
                            problem.trip().constraints().maximumWearsBetweenLaundry())
                    .onlyEnforceIf(allowance);
        }
        objective(model, problem, pieces);
        return new Formulation(model, pieces, assignments, labels);
    }

    private void objective(CpModel model, PackingProblem problem, Map<UUID, BoolVar> pieces) {
        var weights = settings.weights();
        var objective = LinearExpr.newBuilder();
        var groups = new LinkedHashMap<String, List<BoolVar>>();
        for (var candidate : problem.candidates()) {
            var piece = pieces.get(candidate.garment().id());
            long coefficient =
                    weights.itemCount()
                            - weights.versatility()
                                    * PackingObjective.versatility(problem, candidate)
                            - weights.underuse() * PackingObjective.underuse(candidate);
            objective.addTerm(piece, coefficient);
            String group = PackingObjective.redundancyGroup(candidate);
            if (!group.isEmpty())
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(piece);
        }
        var contexts = PackingObjective.contexts(problem);
        long varietyWeight = weights.outfitVariety() / Math.max(1, contexts.size());
        if (varietyWeight > 0)
            for (var context : contexts) {
                var counts = new EnumMap<Slot, Count>(Slot.class);
                for (var slot :
                        List.of(Slot.TOP, Slot.BOTTOM, Slot.DRESS, Slot.SHOES, Slot.OUTERWEAR)) {
                    var matching =
                            problem.candidates().stream()
                                    .filter(
                                            candidate ->
                                                    candidate.slot() == slot
                                                            && candidate.matches(context))
                                    .map(candidate -> pieces.get(candidate.garment().id()))
                                    .toList();
                    var count =
                            model.newIntVar(
                                    0, matching.size(), "count_" + context.index() + "_" + slot);
                    model.addEquality(count, sum(matching));
                    counts.put(slot, new Count(count, matching.size()));
                }
                var tops = counts.get(Slot.TOP);
                var bottoms = counts.get(Slot.BOTTOM);
                long pairsBound = tops.upper() * bottoms.upper();
                var pairs = model.newIntVar(0, pairsBound, "separates_" + context.index());
                model.addMultiplicationEquality(pairs, tops.variable(), bottoms.variable());
                var dresses = counts.get(Slot.DRESS);
                long coreBound = pairsBound + dresses.upper();
                var core = model.newIntVar(0, coreBound, "core_outfits_" + context.index());
                model.addEquality(core, LinearExpr.newBuilder().add(pairs).add(dresses.variable()));
                var layers = counts.get(Slot.OUTERWEAR);
                int optional = problem.requiresOuterwear() ? 0 : 1;
                var layerChoices =
                        model.newIntVar(
                                optional, layers.upper() + optional, "layers_" + context.index());
                model.addEquality(
                        layerChoices, LinearExpr.newBuilder().add(layers.variable()).add(optional));
                var shoes = counts.get(Slot.SHOES);
                long combinationsBound = coreBound * shoes.upper() * (layers.upper() + optional);
                var combinations =
                        model.newIntVar(
                                0, combinationsBound, "possible_outfits_" + context.index());
                model.addMultiplicationEquality(
                        combinations, new LinearArgument[] {core, shoes.variable(), layerChoices});
                var rewarded =
                        model.newIntVar(
                                0,
                                PackingObjective.MAXIMUM_REWARDED_VARIETY,
                                "rewarded_variety_" + context.index());
                model.addMinEquality(
                        rewarded,
                        new LinearArgument[] {
                            combinations,
                            model.newConstant(PackingObjective.MAXIMUM_REWARDED_VARIETY)
                        });
                objective.addTerm(rewarded, -varietyWeight);
            }
        if (weights.redundancy() > 0)
            for (var group : groups.values()) {
                if (group.size() < 2) continue;
                var repeats =
                        model.newIntVar(
                                0, group.size() - 1L, "redundancy_" + group.getFirst().getIndex());
                model.addMaxEquality(
                        repeats, new LinearArgument[] {sum(group).add(-1), model.newConstant(0)});
                objective.addTerm(repeats, weights.redundancy());
            }
        model.minimize(objective);
    }

    private PackingSolution result(
            PackingProblem problem,
            Formulation formulation,
            CpSolver solver,
            CpSolverStatus status) {
        if (status == CpSolverStatus.MODEL_INVALID)
            throw new IllegalStateException("OR-Tools rejected the packing model.");
        if (status == CpSolverStatus.INFEASIBLE) {
            var warnings = new ArrayList<ConstraintWarning>();
            warnings.add(
                    new ConstraintWarning(
                            "INFEASIBLE",
                            "No capsule satisfies all of this trip's hard constraints. Review the maximum count, required pieces, occasions, and rewear allowance.",
                            true));
            var conflicts =
                    solver.sufficientAssumptionsForInfeasibility().stream()
                            .map(formulation.labels()::get)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
            warnings.addAll(conflicts.stream().limit(32).toList());
            if (conflicts.size() > 32)
                warnings.add(
                        new ConstraintWarning(
                                "ADDITIONAL_CONFLICTS",
                                "The conflicting set contains "
                                        + conflicts.size()
                                        + " constraint groups; the first 32 are shown. The set may contain redundant constraints.",
                                true));
            return new PackingSolution(Status.INFEASIBLE, List.of(), List.of(), warnings);
        }
        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE)
            return new PackingSolution(
                    Status.TIME_LIMIT,
                    List.of(),
                    List.of(),
                    List.of(
                            new ConstraintWarning(
                                    "TIME_LIMIT",
                                    "The solver reached its limit without finding a feasible capsule. This does not prove the trip is infeasible. Narrow the trip or try again.",
                                    false)));
        var selected =
                formulation.pieces().entrySet().stream()
                        .filter(entry -> solver.booleanValue(entry.getValue()))
                        .map(Map.Entry::getKey)
                        .toList();
        var outfits = new ArrayList<ScheduledOutfit>();
        for (var demand : problem.demands())
            outfits.add(
                    new ScheduledOutfit(
                            demand.index(),
                            formulation.assignments().get(demand.index()).entrySet().stream()
                                    .filter(entry -> solver.booleanValue(entry.getValue()))
                                    .map(Map.Entry::getKey)
                                    .toList()));
        var warnings =
                status == CpSolverStatus.OPTIMAL
                        ? List.<ConstraintWarning>of()
                        : List.of(
                                new ConstraintWarning(
                                        "OPTIMALITY_NOT_PROVEN",
                                        "This capsule satisfies the hard constraints, but optimality was not proved within the solver's limit.",
                                        false));
        return new PackingSolution(
                status == CpSolverStatus.OPTIMAL ? Status.OPTIMAL : Status.FEASIBLE,
                selected,
                outfits,
                warnings);
    }

    private BoolVar assumption(
            CpModel model,
            Map<Integer, ConstraintWarning> labels,
            String name,
            String code,
            String detail) {
        var literal = model.newBoolVar(name);
        model.addAssumption(literal);
        labels.put(literal.getIndex(), new ConstraintWarning(code, detail, true));
        return literal;
    }

    private LinearExprBuilder sum(Iterable<BoolVar> variables) {
        var sum = LinearExpr.newBuilder();
        variables.forEach(sum::add);
        return sum;
    }

    private record WearPeriod(UUID garment, int period) {}

    private record Count(IntVar variable, long upper) {}

    private record Formulation(
            CpModel model,
            Map<UUID, BoolVar> pieces,
            List<Map<UUID, BoolVar>> assignments,
            Map<Integer, ConstraintWarning> labels) {}
}
