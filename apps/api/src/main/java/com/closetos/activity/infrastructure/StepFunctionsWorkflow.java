package com.closetos.activity.infrastructure;

import com.closetos.activity.application.WorkflowSlots;
import com.closetos.media.api.WorkflowCapacityUnavailable;
import com.closetos.media.api.WorkflowJob;
import com.closetos.media.api.WorkflowOrchestratorPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(
        name = "closetos.media.workflow-mode",
        havingValue = "aws",
        matchIfMissing = true)
class StepFunctionsWorkflow implements WorkflowOrchestratorPort {
    private final SfnClient sfn;
    private final JsonMapper json;
    private final String stateMachine;
    private final WorkflowSlots slots;

    StepFunctionsWorkflow(
            JsonMapper json,
            SfnClient sfn,
            WorkflowSlots slots,
            @Value("${closetos.media.state-machine-arn:}") String stateMachine) {
        this.sfn = sfn;
        this.slots = slots;
        this.json = json;
        this.stateMachine = stateMachine;
    }

    @Override
    public void reserve(WorkflowJob job) {
        if (!stateMachine.matches(
                "arn:aws(?:-[a-z]+)?:states:[a-z0-9-]+:[0-9]{12}:stateMachine:[A-Za-z0-9_-]+"))
            throw new IllegalStateException(
                    "Configure an unqualified Standard processing state machine ARN.");
        slots.reserve(job, stateMachine);
    }

    @Override
    public void abandon(WorkflowJob job) {
        slots.abandon(job.jobId());
    }

    @Override
    public WorkflowStart start(WorkflowJob job) {
        if (stateMachine.isBlank())
            throw new IllegalStateException("Processing state machine is not configured");
        String expected =
                stateMachine.replace(":stateMachine:", ":execution:") + ":" + job.executionName();
        if (!slots.beginAttempt(job.jobId(), expected)) throw new WorkflowCapacityUnavailable();
        try {
            var response =
                    sfn.startExecution(
                            StartExecutionRequest.builder()
                                    .stateMachineArn(stateMachine)
                                    .name(job.executionName())
                                    .input(json.writeValueAsString(job))
                                    .build());
            return new WorkflowStart(response.executionArn(), null);
        } catch (ExecutionAlreadyExistsException exception) {
            String execution =
                    stateMachine.replace(":stateMachine:", ":execution:")
                            + ":"
                            + job.executionName();
            return new WorkflowStart(execution, null);
        }
    }
}
