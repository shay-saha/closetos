package com.closetos.activity.infrastructure;

import com.closetos.activity.application.ProcessingResults;
import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.activity.application.WorkflowSlots;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.WorkflowJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.ExecutionDoesNotExistException;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "closetos.media.aws-consumer-enabled", havingValue = "true")
class WorkflowReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowReconciler.class);
    private final SfnClient sfn;
    private final WorkflowSlots slots;
    private final ProcessingAccess processing;
    private final ProcessingTransitions transitions;
    private final ProcessingResults results;
    private final ObjectStoragePort storage;
    private final JsonMapper json;
    private final MeterRegistry metrics;

    WorkflowReconciler(
            SfnClient sfn,
            WorkflowSlots slots,
            ProcessingAccess processing,
            ProcessingTransitions transitions,
            ProcessingResults results,
            ObjectStoragePort storage,
            JsonMapper json,
            MeterRegistry metrics) {
        this.sfn = sfn;
        this.slots = slots;
        this.processing = processing;
        this.transitions = transitions;
        this.results = results;
        this.storage = storage;
        this.json = json;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${closetos.media.reconcile-ms:5000}")
    void reconcile() {
        for (var slot : slots.pending()) {
            try {
                inspect(slot);
            } catch (RuntimeException exception) {
                metrics.counter("processing.workflow.reconciliation.failures").increment();
                LOG.warn(
                        "Workflow reconciliation failed for job {} ({})",
                        slot.jobId(),
                        exception.getClass().getSimpleName());
                slots.observed(slot.jobId(), "UNAVAILABLE");
            }
        }
    }

    private void inspect(WorkflowSlots.Slot slot) {
        ExecutionStatus status;
        try {
            status =
                    sfn.describeExecution(
                                    DescribeExecutionRequest.builder()
                                            .executionArn(slot.executionArn())
                                            .includedData("METADATA_ONLY")
                                            .build())
                            .status();
        } catch (ExecutionDoesNotExistException exception) {
            slots.observed(slot.jobId(), "NOT_FOUND");
            if (processing.runnable(slot.jobId())) return;
            if (!slot.attempted()) {
                slots.abandon(slot.jobId());
                return;
            }
            // Claim the abandoned name with input rejected before RunTask. A late start cannot
            // create another execution.
            try {
                sfn.startExecution(
                        StartExecutionRequest.builder()
                                .stateMachineArn(slot.stateMachineArn())
                                .name(slot.executionName())
                                .input("{}")
                                .build());
            } catch (ExecutionAlreadyExistsException alreadyStarted) {
                // The original execution won the race; retain its capacity until a terminal
                // observation.
            }
            return;
        }
        slots.observed(slot.jobId(), status == null ? "UNKNOWN" : status.toString());
        if (status != ExecutionStatus.SUCCEEDED
                && status != ExecutionStatus.FAILED
                && status != ExecutionStatus.TIMED_OUT
                && status != ExecutionStatus.ABORTED) return;
        var job = json.readValue(slot.payload(), WorkflowJob.class);
        if (processing.context(slot.jobId()).isEmpty()) {
            storage.deletePrefix(job.imagePrefix());
        } else if (processing.runnable(slot.jobId())) {
            if (status == ExecutionStatus.SUCCEEDED)
                results.manifest(job, "reconcile:" + slot.jobId());
            else
                transitions.failed(
                        slot.jobId(),
                        "PROCESSING_FAILURE",
                        "We could not process this photograph. Try again or add the details yourself.");
        }
        slots.finished(slot.jobId());
    }
}
