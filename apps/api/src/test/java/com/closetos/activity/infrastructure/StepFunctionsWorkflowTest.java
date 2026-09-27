package com.closetos.activity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.closetos.activity.application.WorkflowSlots;
import com.closetos.media.api.WorkflowCapacityUnavailable;
import com.closetos.media.api.WorkflowJob;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import tools.jackson.databind.json.JsonMapper;

class StepFunctionsWorkflowTest {
    private static final String MACHINE =
            "arn:aws:states:eu-west-2:123456789012:stateMachine:closetos-dev-media";
    private final SfnClient sfn = mock(SfnClient.class);
    private final WorkflowSlots slots = mock(WorkflowSlots.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final StepFunctionsWorkflow workflow =
            new StepFunctionsWorkflow(json, sfn, slots, MACHINE);
    private final WorkflowJob job =
            new WorkflowJob(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "source",
                    "output",
                    "image/png",
                    10,
                    "checksum",
                    "1-r1",
                    "request");

    @Test
    void durablyMarksTheAttemptBeforeSendingTheIdempotentStartRequest() {
        when(slots.beginAttempt(eq(job.jobId()), anyString())).thenReturn(true);
        String arn = MACHINE.replace(":stateMachine:", ":execution:") + ":" + job.executionName();
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(arn).build());
        workflow.reserve(job);
        assertThat(workflow.start(job).executionArn()).isEqualTo(arn);
        var ordered = inOrder(slots, sfn);
        ordered.verify(slots).reserve(job, MACHINE);
        ordered.verify(slots).beginAttempt(job.jobId(), arn);
        ordered.verify(sfn)
                .startExecution(
                        argThat(
                                (StartExecutionRequest request) ->
                                        request.name().equals(job.executionName())
                                                && request.input()
                                                        .equals(json.writeValueAsString(job))));
    }

    @Test
    void aLostReservationCannotSendAnExternalStartRequest() {
        assertThatThrownBy(() -> workflow.start(job))
                .isInstanceOf(WorkflowCapacityUnavailable.class);
        verifyNoInteractions(sfn);
    }

    @Test
    void aStartTimeoutKeepsItsReservationForReplayAndReconciliation() {
        when(slots.beginAttempt(eq(job.jobId()), anyString())).thenReturn(true);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(SdkClientException.create("Unknown start outcome"));
        assertThatThrownBy(() -> workflow.start(job)).isInstanceOf(SdkClientException.class);
        verify(slots, never()).finished(any());
        verify(slots, never()).abandon(any());
    }

    @Test
    void duplicateNamesReturnTheOriginalExecutionIdentity() {
        when(slots.beginAttempt(eq(job.jobId()), anyString())).thenReturn(true);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(ExecutionAlreadyExistsException.builder().build());
        assertThat(workflow.start(job).executionArn())
                .isEqualTo(
                        MACHINE.replace(":stateMachine:", ":execution:")
                                + ":"
                                + job.executionName());
        verify(slots, never()).finished(any());
    }

    @Test
    void invalidOrQualifiedStateMachinesCannotReserveCapacity() {
        for (String machine :
                new String[] {
                    "",
                    MACHINE + ":PROD",
                    "arn:aws:states:eu-west-2:123456789012:stateMachine:other/map"
                }) {
            assertThatThrownBy(
                            () -> new StepFunctionsWorkflow(json, sfn, slots, machine).reserve(job))
                    .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(slots, sfn);
    }
}
