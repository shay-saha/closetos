package com.closetos.media.api;

import java.util.Optional;
import java.util.UUID;

public interface ProcessingAccess {
    Optional<WorkflowJob> uploaded(ImageRecord image, String eventId);

    WorkflowJob retry(UUID image, UUID wardrobe);

    Optional<WorkflowJob> context(UUID jobId);

    ProcessingSnapshot snapshot(UUID image, UUID wardrobe);

    boolean started(WorkflowJob job, String executionArn);

    boolean complete(ProcessingResult result, String eventId);

    void fail(UUID jobId, String code, String detail);

    void originalDeleted(UUID imageId);

    record ProcessingSnapshot(
            UUID imageId,
            UUID garmentId,
            ProcessingStatus state,
            int attemptCount,
            String failureCode,
            String failureDetail,
            boolean canRetry) {}
}
