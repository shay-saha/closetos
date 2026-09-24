package com.closetos.packing.application;

import static com.closetos.packing.application.PackingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingSolverPort;
import com.closetos.platform.api.DomainException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PackingOptimisationTest {
    private final PackingLists lists = mock(PackingLists.class);
    private final PackingSolverPort solver = mock(PackingSolverPort.class);
    private final PackingVerifier verifier = new PackingVerifier();
    private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void missingNativeSolverNeverStoresAFallbackCapsule() {
        var dress = piece("Dress", GarmentCategory.DRESS, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var preparation = new PackingRules().prepare(trip(1, 5, 0, 1), List.of(dress, shoes));
        when(lists.snapshot(id, 0))
                .thenReturn(new PackingSnapshot(id, 0, preparation, List.of(dress, shoes)));
        var optimisation = new PackingOptimisation(lists, Optional.empty(), verifier);
        assertThatThrownBy(() -> optimisation.optimise(id, 0))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).status())
                .isEqualTo(503);
        verify(lists, never()).saveSolution(any(), any(), anyBoolean());
    }

    @Test
    void provedStructuralInfeasibilityIsSavedWithoutCallingTheSolver() {
        var preparation = new PackingRules().prepare(trip(1, 5, 0, 1), List.of());
        var snapshot = new PackingSnapshot(id, 0, preparation, List.of());
        when(lists.snapshot(id, 0)).thenReturn(snapshot);
        var optimisation = new PackingOptimisation(lists, Optional.of(solver), verifier);
        optimisation.optimise(id, 0);
        verifyNoInteractions(solver);
        var capture = org.mockito.ArgumentCaptor.forClass(PackingSolution.class);
        verify(lists).saveSolution(eq(snapshot), capture.capture(), eq(false));
        assertThat(capture.getValue().status()).isEqualTo(PackingSolution.Status.INFEASIBLE);
        assertThat(capture.getValue().selectedGarments()).isEmpty();
        assertThat(capture.getValue().warnings()).anyMatch(warning -> warning.blocking());
    }

    @Test
    void invalidSolverResultsCannotReachPersistence() {
        var dress = piece("Dress", GarmentCategory.DRESS, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var preparation = new PackingRules().prepare(trip(1, 5, 0, 1), List.of(dress, shoes));
        when(lists.snapshot(id, 0))
                .thenReturn(new PackingSnapshot(id, 0, preparation, List.of(dress, shoes)));
        when(solver.solve(preparation.problem()))
                .thenReturn(solution(List.of(dress), List.of(List.of(dress))));
        var optimisation = new PackingOptimisation(lists, Optional.of(solver), verifier);
        assertThatThrownBy(() -> optimisation.optimise(id, 0))
                .isInstanceOf(PackingVerifier.InvalidPackingSolution.class);
        verify(lists, never()).saveSolution(any(), any(), anyBoolean());
    }

    @Test
    void aManualCapsuleIsVerifiedButNeverClaimsSolverOptimality() {
        var dress = piece("Dress", GarmentCategory.DRESS, "mild");
        var shoes = piece("Shoes", GarmentCategory.SHOES, "mild");
        var preparation = new PackingRules().prepare(trip(1, 5, 0, 1), List.of(dress, shoes));
        var snapshot = new PackingSnapshot(id, 0, preparation, List.of(dress, shoes));
        when(lists.snapshot(id, 0)).thenReturn(snapshot);
        var optimisation = new PackingOptimisation(lists, Optional.empty(), verifier);
        optimisation.override(
                id, 0, solution(List.of(dress, shoes), List.of(List.of(dress, shoes))));
        var capture = org.mockito.ArgumentCaptor.forClass(PackingSolution.class);
        verify(lists).saveSolution(eq(snapshot), capture.capture(), eq(true));
        assertThat(capture.getValue().status()).isEqualTo(PackingSolution.Status.FEASIBLE);
        assertThat(verifier.explanations(preparation.problem(), capture.getValue(), true))
                .anyMatch(
                        text ->
                                text.contains("manual capsule")
                                        && text.contains("Optimality is not claimed"));
        assertThatThrownBy(
                        () ->
                                optimisation.override(
                                        id, 0, solution(List.of(dress), List.of(List.of(dress)))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("shoes");
    }
}
