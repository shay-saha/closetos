package com.closetos.activity.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.closetos.activity.application.ProcessingResults;
import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.media.api.*;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.platform.api.OutboxQueue;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProcessingDispatcherTest {
    private final OutboxQueue queue = mock(OutboxQueue.class);
    private final WorkflowOrchestratorPort workflow = mock(WorkflowOrchestratorPort.class);
    private final ProcessingTransitions transitions = mock(ProcessingTransitions.class);
    private final ProcessingAccess processing = mock(ProcessingAccess.class);
    private final ProcessingResults results = mock(ProcessingResults.class);
    private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ProcessingDispatcher dispatcher =
            new ProcessingDispatcher(
                    queue, workflow, transitions, processing, results, storage, json);
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

    private OutboxEntry event(int attempts) {
        var event =
                new OutboxEntry(
                        UUID.randomUUID(),
                        job.imageId(),
                        "START_PROCESSING",
                        json.writeValueAsString(job),
                        attempts);
        when(queue.claim(any())).thenReturn(List.of(event));
        when(processing.context(job.jobId())).thenReturn(Optional.of(job));
        return event;
    }

    @Test
    void capacityPressureDefersWithoutChargingAFailureAttemptOrChangingUserState() {
        var event = event(10);
        doThrow(new WorkflowCapacityUnavailable()).when(workflow).reserve(job);
        dispatcher.publish();
        verify(queue).defer(event, Duration.ofSeconds(5));
        verify(queue, never()).failed(any(), anyString(), anyBoolean());
        verify(queue, never()).published(any());
        verifyNoInteractions(transitions, results, storage);
        verify(workflow, never()).start(any());
    }

    @Test
    void abandonsAnUnusedReservationWhenTheImageIsDeletedOrRetrySupersedesTheJob() {
        var event = event(1);
        when(transitions.started(job, null)).thenReturn(false);
        dispatcher.publish();
        verify(workflow).abandon(job);
        verify(workflow, never()).start(any());
        verify(queue).published(event);
    }

    @Test
    void localCompletionPurgesAssetsWrittenAfterTheirImageWasDeleted() {
        var event = event(1);
        when(processing.context(job.jobId())).thenReturn(Optional.of(job), Optional.empty());
        when(transitions.started(job, null)).thenReturn(true);
        var result = mock(ProcessingResult.class);
        when(workflow.start(job))
                .thenReturn(new WorkflowOrchestratorPort.WorkflowStart("local", result));
        dispatcher.publish();
        verify(results).accept(result, "result:" + job.jobId());
        verify(storage).deletePrefix(job.imagePrefix());
        verify(queue).published(event);
    }

    @Test
    void reservesCapacityBeforeMarkingAndStartingAJob() {
        var event = event(1);
        when(transitions.started(job, null)).thenReturn(true);
        when(workflow.start(job))
                .thenReturn(new WorkflowOrchestratorPort.WorkflowStart("execution", null));
        dispatcher.publish();
        var ordered = inOrder(workflow, transitions, queue);
        ordered.verify(workflow).reserve(job);
        ordered.verify(transitions).started(job, null);
        ordered.verify(workflow).start(job);
        ordered.verify(transitions).started(job, "execution");
        ordered.verify(queue).published(event);
    }
}
