package com.closetos.media.api;

public interface WorkflowOrchestratorPort {
    default void reserve(WorkflowJob job) {}

    default void abandon(WorkflowJob job) {}

    WorkflowStart start(WorkflowJob job);

    record WorkflowStart(String executionArn, ProcessingResult completedResult) {}
}
