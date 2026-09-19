package com.closetos.media.api;

public interface WorkflowOrchestratorPort {
    WorkflowStart start(WorkflowJob job);

    record WorkflowStart(String executionArn, ProcessingResult completedResult) {}
}
