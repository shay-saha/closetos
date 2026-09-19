package com.closetos.activity.infrastructure;

import com.closetos.media.api.WorkflowJob;
import com.closetos.media.api.WorkflowOrchestratorPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
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

    StepFunctionsWorkflow(
            JsonMapper json,
            @Value("${closetos.aws.region:eu-west-2}") String region,
            @Value("${closetos.media.state-machine-arn:}") String stateMachine) {
        this.sfn = SfnClient.builder().region(Region.of(region)).build();
        this.json = json;
        this.stateMachine = stateMachine;
    }

    @Override
    public WorkflowStart start(WorkflowJob job) {
        if (stateMachine.isBlank())
            throw new IllegalStateException("Processing state machine is not configured");
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
