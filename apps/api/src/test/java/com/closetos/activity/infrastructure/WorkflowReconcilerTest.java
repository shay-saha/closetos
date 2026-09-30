package com.closetos.activity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.closetos.activity.application.ProcessingResults;
import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.activity.application.WorkflowSlots;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.WorkflowJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;
import tools.jackson.databind.json.JsonMapper;

class WorkflowReconcilerTest {
    private static final String MACHINE =
            "arn:aws:states:eu-west-2:123456789012:stateMachine:closetos-dev-media";
    private final JsonMapper json = JsonMapper.builder().build();
    private final SfnClient sfn = mock(SfnClient.class);
    private final WorkflowSlots slots = mock(WorkflowSlots.class);
    private final AwsMediaTasks workers = mock(AwsMediaTasks.class);
    private final MediaExecutionHistory history = mock(MediaExecutionHistory.class);
    private final ProcessingAccess processing = mock(ProcessingAccess.class);
    private final ProcessingTransitions transitions = mock(ProcessingTransitions.class);
    private final ProcessingResults results = mock(ProcessingResults.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final WorkflowReconciler reconciler =
            new WorkflowReconciler(
                    sfn,
                    slots,
                    workers,
                    history,
                    processing,
                    transitions,
                    results,
                    storage,
                    json,
                    metrics);
    private final WorkflowJob job = fixture();

    private WorkflowJob fixture() {
        UUID image = UUID.randomUUID(), garment = UUID.randomUUID();
        String prefix =
                "users/" + UUID.randomUUID() + "/garments/" + garment + "/images/" + image + "/";
        return new WorkflowJob(
                UUID.randomUUID(),
                image,
                garment,
                UUID.randomUUID(),
                prefix + "original.png",
                prefix + "pipelines/1-r1/",
                "image/png",
                10,
                "checksum",
                "1-r1",
                "request");
    }

    private void slot(boolean attempted) {
        when(slots.pending())
                .thenReturn(
                        List.of(
                                new WorkflowSlots.Slot(
                                        job.jobId(),
                                        MACHINE.replace(":stateMachine:", ":execution:")
                                                + ":"
                                                + job.executionName(),
                                        MACHINE,
                                        job.executionName(),
                                        json.writeValueAsString(job),
                                        attempted)));
    }

    private void status(ExecutionStatus status) {
        when(history.inspect(anyString(), eq(status)))
                .thenReturn(new MediaExecutionHistory.SubmissionHistory(java.util.Set.of(), true));
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(status).build());
    }

    @Test
    void runningAndUnknownExecutionsKeepTheirCapacityWithoutReadingPhotographs() {
        slot(true);
        when(processing.context(job.jobId())).thenReturn(Optional.of(job));
        for (ExecutionStatus status :
                new ExecutionStatus[] {
                    ExecutionStatus.RUNNING,
                    ExecutionStatus.PENDING_REDRIVE,
                    ExecutionStatus.UNKNOWN_TO_SDK_VERSION,
                    null
                }) {
            status(status);
            reconciler.reconcile();
        }
        verify(slots, never()).finished(any());
        verify(processing).context(job.jobId());
        verifyNoInteractions(transitions, results, storage);
        verify(sfn, times(4))
                .describeExecution(
                        argThat(
                                (DescribeExecutionRequest request) ->
                                        request.includedData() == IncludedData.METADATA_ONLY));
    }

    @Test
    void recoversACompletedManifestThroughTheExistingVerificationBoundary() {
        slot(true);
        status(ExecutionStatus.SUCCEEDED);
        when(processing.context(job.jobId())).thenReturn(Optional.of(job));
        when(processing.runnable(job.jobId())).thenReturn(true);
        reconciler.reconcile();
        var ordered = inOrder(results, slots);
        ordered.verify(results).manifest(job, "reconcile:" + job.jobId());
        ordered.verify(slots).finished(job.jobId());
        verifyNoInteractions(transitions, storage);
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionStatus.class,
            names = {"FAILED", "TIMED_OUT", "ABORTED"})
    void makesTerminalFailuresVisibleBeforeReleasingTheirCapacity(ExecutionStatus status) {
        slot(true);
        status(status);
        when(processing.context(job.jobId())).thenReturn(Optional.of(job));
        when(processing.runnable(job.jobId())).thenReturn(true);
        reconciler.reconcile();
        var ordered = inOrder(transitions, slots);
        ordered.verify(transitions).failed(eq(job.jobId()), eq("PROCESSING_FAILURE"), anyString());
        ordered.verify(slots).finished(job.jobId());
        verifyNoInteractions(results);
    }

    @Test
    void doesNotReapplyResultsAlreadyConsumedFromTheQueue() {
        slot(true);
        status(ExecutionStatus.SUCCEEDED);
        when(processing.context(job.jobId())).thenReturn(Optional.of(job));
        reconciler.reconcile();
        verify(slots).finished(job.jobId());
        verifyNoInteractions(results, transitions, storage);
    }

    @Test
    void purgesOutputsWrittenAfterAUserDeletedAnInFlightPhotograph() {
        slot(true);
        status(ExecutionStatus.SUCCEEDED);
        when(processing.context(job.jobId())).thenReturn(Optional.empty());
        reconciler.reconcile();
        var ordered = inOrder(storage, slots);
        ordered.verify(storage).deletePrefix(job.imagePrefix());
        ordered.verify(slots).finished(job.jobId());
        verifyNoInteractions(results, transitions);
    }

    @Test
    void aTerminalExecutionCannotPurgePhotographsWhileAWorkerIsStillRunning() {
        slot(true);
        status(ExecutionStatus.ABORTED);
        String task =
                "arn:aws:ecs:eu-west-2:123456789012:task/closetos-dev/0123456789abcdef0123456789abcdef";
        when(slots.unconfirmedTasks(job.jobId())).thenReturn(java.util.Set.of(task));
        when(workers.inspect(job.jobId(), java.util.Set.of(task), true))
                .thenReturn(java.util.Map.of(task, false));
        reconciler.reconcile();
        verify(workers).inspect(job.jobId(), java.util.Set.of(task), true);
        verify(slots, never()).finished(any());
        verifyNoInteractions(storage, results, transitions, processing);
        assertThat(metrics.get("processing.workflow.workers.pending").counter().count())
                .isEqualTo(1);
    }

    @Test
    void storesWorkerStopConfirmationBeforePurgeAndCapacityRelease() {
        slot(true);
        status(ExecutionStatus.ABORTED);
        String task =
                "arn:aws:ecs:eu-west-2:123456789012:task/closetos-dev/0123456789abcdef0123456789abcdef";
        when(slots.unconfirmedTasks(job.jobId()))
                .thenReturn(java.util.Set.of(task), java.util.Set.of());
        when(workers.inspect(job.jobId(), java.util.Set.of(task), true))
                .thenReturn(java.util.Map.of(task, true));
        reconciler.reconcile();
        var ordered = inOrder(slots, storage);
        ordered.verify(slots).taskStopped(job.jobId(), task);
        ordered.verify(storage).deletePrefix(job.imagePrefix());
        ordered.verify(slots).finished(job.jobId());
    }

    @Test
    void ambiguousSubmissionsRetainCapacityEvenWhenTaskDiscoveryIsEmpty() {
        slot(true);
        status(ExecutionStatus.TIMED_OUT);
        when(history.inspect(anyString(), eq(ExecutionStatus.TIMED_OUT)))
                .thenReturn(new MediaExecutionHistory.SubmissionHistory(java.util.Set.of(), false));
        reconciler.reconcile();
        verify(slots, never()).finished(any());
        verifyNoInteractions(storage, results, transitions, processing);
    }

    @Test
    void failedWorkerInspectionRetainsCapacityWithoutPurgingPhotographs() {
        slot(true);
        status(ExecutionStatus.FAILED);
        when(workers.discover(job.jobId()))
                .thenThrow(new IllegalStateException("Worker unavailable"));
        reconciler.reconcile();
        verify(slots, never()).finished(any());
        verifyNoInteractions(storage, results, transitions, processing);
        assertThat(metrics.get("processing.workflow.reconciliation.failures").counter().count())
                .isEqualTo(1);
    }

    @Test
    void requestsCancellationOfAnOrphanedRunningExecutionAndWaitsForAnotherObservation() {
        slot(true);
        status(ExecutionStatus.RUNNING);
        reconciler.reconcile();
        verify(sfn)
                .stopExecution(
                        argThat(
                                (StopExecutionRequest request) ->
                                        request.executionArn()
                                                        .equals(
                                                                MACHINE.replace(
                                                                                ":stateMachine:",
                                                                                ":execution:")
                                                                        + ":"
                                                                        + job.executionName())
                                                && request.error().equals("MediaRemoved")));
        verify(slots, never()).finished(any());
        verifyNoInteractions(storage, results, transitions, history);
    }

    @Test
    void keepsCapacityAndReportsARecoveryFailureWhenOutputVerificationFails() {
        slot(true);
        status(ExecutionStatus.SUCCEEDED);
        when(processing.context(job.jobId())).thenReturn(Optional.of(job));
        when(processing.runnable(job.jobId())).thenReturn(true);
        doThrow(new IllegalStateException("Invalid manifest"))
                .when(results)
                .manifest(any(), anyString());
        reconciler.reconcile();
        verify(slots, never()).finished(any());
        assertThat(metrics.get("processing.workflow.reconciliation.failures").counter().count())
                .isEqualTo(1);
    }

    @Test
    void keepsCapacityUntilDeletionCleanupSucceeds() {
        slot(true);
        status(ExecutionStatus.FAILED);
        when(processing.context(job.jobId())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("Storage unavailable"))
                .when(storage)
                .deletePrefix(anyString());
        reconciler.reconcile();
        verify(slots, never()).finished(any());
    }

    @Test
    void anUnavailableStatusCallCannotReleaseOrRestartAWorker() {
        slot(true);
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(SdkClientException.create("Unavailable"));
        reconciler.reconcile();
        verify(slots, never()).finished(any());
        verify(sfn, never()).startExecution(any(StartExecutionRequest.class));
        assertThat(metrics.get("processing.workflow.reconciliation.failures").counter().count())
                .isEqualTo(1);
    }

    @Test
    void eventuallyConsistentNotFoundKeepsRunnableReservationsForOutboxReplay() {
        slot(true);
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(ExecutionDoesNotExistException.builder().build());
        when(processing.runnable(job.jobId())).thenReturn(true);
        reconciler.reconcile();
        verify(sfn, never()).startExecution(any(StartExecutionRequest.class));
        verify(slots, never()).finished(any());
        verify(slots, never()).abandon(any());
    }

    @Test
    void unusedAbandonedReservationsCanBeReleasedWithoutLaunchingAnExecution() {
        slot(false);
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(ExecutionDoesNotExistException.builder().build());
        reconciler.reconcile();
        verify(slots).abandon(job.jobId());
        verify(slots, never()).finished(any());
        verify(sfn, never()).startExecution(any(StartExecutionRequest.class));
    }

    @Test
    void closesAnAmbiguousAbandonedExecutionNameWithoutLaunchingMediaTasks() {
        slot(true);
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(ExecutionDoesNotExistException.builder().build());
        reconciler.reconcile();
        verify(sfn)
                .startExecution(
                        argThat(
                                (StartExecutionRequest request) ->
                                        request.name().equals(job.executionName())
                                                && request.stateMachineArn().equals(MACHINE)
                                                && request.input().equals("{}")));
        verify(slots, never()).finished(any());
        verify(slots, never()).abandon(any());
    }

    @Test
    void aLateOriginalStartWinningTheAbandonmentRaceKeepsItsReservation() {
        slot(true);
        when(sfn.describeExecution(any(DescribeExecutionRequest.class)))
                .thenThrow(ExecutionDoesNotExistException.builder().build());
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(ExecutionAlreadyExistsException.builder().build());
        reconciler.reconcile();
        verify(slots, never()).finished(any());
        verify(slots, never()).abandon(any());
        assertThat(metrics.find("processing.workflow.reconciliation.failures").counter()).isNull();
    }
}
