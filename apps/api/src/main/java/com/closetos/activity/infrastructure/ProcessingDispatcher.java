package com.closetos.activity.infrastructure;

import com.closetos.activity.application.ProcessingResults;
import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.WorkflowCapacityUnavailable;
import com.closetos.media.api.WorkflowJob;
import com.closetos.media.api.WorkflowOrchestratorPort;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.platform.api.OutboxQueue;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "closetos.media.dispatch-enabled", havingValue = "true")
class ProcessingDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingDispatcher.class);
    private final OutboxQueue queue;
    private final WorkflowOrchestratorPort workflow;
    private final ProcessingTransitions transitions;
    private final ProcessingAccess processing;
    private final ProcessingResults results;
    private final ObjectStoragePort storage;
    private final JsonMapper json;

    ProcessingDispatcher(
            OutboxQueue queue,
            WorkflowOrchestratorPort workflow,
            ProcessingTransitions transitions,
            ProcessingAccess processing,
            ProcessingResults results,
            ObjectStoragePort storage,
            JsonMapper json) {
        this.queue = queue;
        this.workflow = workflow;
        this.transitions = transitions;
        this.processing = processing;
        this.results = results;
        this.storage = storage;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${closetos.media.dispatch-ms:1000}")
    void publish() {
        for (OutboxEntry event :
                queue.claim(Set.of("START_PROCESSING", "DELETE_MEDIA", "DELETE_ORIGINAL"))) {
            try {
                dispatch(event);
                queue.published(event);
            } catch (WorkflowCapacityUnavailable exception) {
                queue.defer(event, Duration.ofSeconds(5));
            } catch (RuntimeException exception) {
                boolean terminal =
                        exception instanceof DomainException || event.publishAttempts() >= 5;
                LOG.error(
                        "Could not publish media event {} ({})",
                        event.id(),
                        event.eventType(),
                        exception);
                queue.failed(event, exception.getClass().getSimpleName(), terminal);
                if (terminal && "START_PROCESSING".equals(event.eventType())) {
                    var job = json.readValue(event.payload(), WorkflowJob.class);
                    transitions.failed(
                            job.jobId(),
                            "PROCESSING_FAILURE",
                            "We could not process this photograph. Try again or add the details yourself.");
                }
            }
        }
    }

    private void dispatch(OutboxEntry event) {
        switch (event.eventType()) {
            case "START_PROCESSING" -> {
                WorkflowJob job = json.readValue(event.payload(), WorkflowJob.class);
                if (processing.context(job.jobId()).isEmpty()) return;
                workflow.reserve(job);
                if (!transitions.started(job, null)) {
                    workflow.abandon(job);
                    return;
                }
                var start = workflow.start(job);
                transitions.started(job, start.executionArn());
                if (start.completedResult() != null) {
                    results.accept(start.completedResult(), "result:" + job.jobId());
                    if (processing.context(job.jobId()).isEmpty())
                        storage.deletePrefix(job.imagePrefix());
                }
            }
            case "DELETE_MEDIA" ->
                    storage.deletePrefix(json.readTree(event.payload()).get("prefix").asText());
            case "DELETE_ORIGINAL" -> {
                var payload = json.readTree(event.payload());
                storage.delete(payload.get("sourceKey").asText());
                processing.originalDeleted(UUID.fromString(payload.get("imageId").asText()));
            }
            default ->
                    throw new IllegalStateException(
                            "Unsupported outbox event: " + event.eventType());
        }
    }
}
