package com.closetos.activity.infrastructure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryRequest;
import software.amazon.awssdk.services.sfn.model.HistoryEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "closetos.media.aws-consumer-enabled", havingValue = "true")
class MediaExecutionHistory {
    private final SfnClient sfn;
    private final JsonMapper json;
    private final AwsMediaTasks workers;

    MediaExecutionHistory(SfnClient sfn, JsonMapper json, AwsMediaTasks workers) {
        this.sfn = sfn;
        this.json = json;
        this.workers = workers;
    }

    SubmissionHistory inspect(String execution, ExecutionStatus status) {
        var events = new HashMap<Long, HistoryEvent>();
        var scheduled = new HashSet<Long>();
        var submitted = new HashSet<Long>();
        var arns = new LinkedHashSet<String>();
        var tokens = new HashSet<String>();
        long lastId = 0;
        HistoryEvent last = null;
        String token = null;
        do {
            var page =
                    sfn.getExecutionHistory(
                            GetExecutionHistoryRequest.builder()
                                    .executionArn(execution)
                                    .includeExecutionData(true)
                                    .reverseOrder(false)
                                    .maxResults(1000)
                                    .nextToken(token)
                                    .build());
            for (var event : page.events()) {
                Long previousId = event.previousEventId();
                if (Long.valueOf(1).equals(event.id()) && previousId == null) previousId = 0L;
                if (event.id() == null
                        || event.id() != ++lastId
                        || previousId == null
                        || previousId < 0
                        || previousId >= event.id())
                    throw new IllegalStateException("Execution history is incomplete.");
                events.put(event.id(), event);
                last = event;
                var scheduling = event.taskScheduledEventDetails();
                if (scheduling != null && "ecs".equals(scheduling.resourceType())) {
                    requireRunTask(scheduling.resource());
                    scheduled.add(event.id());
                }
                var submission = event.taskSubmittedEventDetails();
                if (submission != null && "ecs".equals(submission.resourceType())) {
                    requireRunTask(submission.resource());
                    long parent = schedulingEvent(event, events, scheduled);
                    if (!submitted.add(parent))
                        throw new IllegalStateException("Worker submission is ambiguous.");
                    if (submission.outputDetails() != null
                            && Boolean.TRUE.equals(submission.outputDetails().truncated()))
                        throw new IllegalStateException("Worker submission output is incomplete.");
                    collectTasks(submission.output(), arns);
                }
            }
            token = page.nextToken();
            if (lastId > 25000 || (token != null && (!tokens.add(token) || tokens.size() >= 25)))
                throw new IllegalStateException("Execution history exceeded its bounded response.");
        } while (token != null);
        String terminal =
                switch (status) {
                    case SUCCEEDED -> "ExecutionSucceeded";
                    case FAILED -> "ExecutionFailed";
                    case ABORTED -> "ExecutionAborted";
                    case TIMED_OUT -> "ExecutionTimedOut";
                    default ->
                            throw new IllegalArgumentException(
                                    "Worker submissions require a terminal execution.");
                };
        if (events.get(1L) == null
                || !"ExecutionStarted".equals(events.get(1L).typeAsString())
                || last == null
                || !terminal.equals(last.typeAsString()))
            throw new IllegalStateException("Execution history has no confirmed terminal event.");
        // A scheduled call without a task handle may have launched a worker before timing out.
        // Retain its reservation rather than interpreting a missing handle as no worker.
        return new SubmissionHistory(Set.copyOf(arns), submitted.equals(scheduled));
    }

    private long schedulingEvent(
            HistoryEvent event, Map<Long, HistoryEvent> events, Set<Long> scheduled) {
        long parent = event.previousEventId();
        while (parent != 0) {
            if (scheduled.contains(parent)) return parent;
            var previous = events.get(parent);
            if (previous == null) break;
            parent = previous.previousEventId() == null ? 0 : previous.previousEventId();
        }
        throw new IllegalStateException("Worker submission has no scheduling event.");
    }

    private void requireRunTask(String resource) {
        if (!"runTask.sync".equals(resource))
            throw new IllegalStateException("Unexpected media workflow resource.");
    }

    private void collectTasks(String output, Set<String> arns) {
        if (output == null || output.isBlank())
            throw new IllegalStateException("Worker submission has no output.");
        JsonNode result = json.readTree(output);
        if (result.has("Failures")
                && (!result.get("Failures").isArray() || !result.get("Failures").isEmpty()))
            throw new IllegalStateException("Worker submission includes unconfirmed failures.");
        if (result.has("Tasks")) {
            JsonNode tasks = result.get("Tasks");
            if (!tasks.isArray() || tasks.isEmpty() || tasks.size() > 32)
                throw new IllegalStateException("Worker submission has no bounded task list.");
            for (JsonNode task : tasks) addTask(task.get("TaskArn"), arns);
        } else {
            addTask(result.get("TaskArn"), arns);
        }
        if (arns.size() > 32)
            throw new IllegalStateException("Too many worker submissions for a job.");
    }

    private void addTask(JsonNode arn, Set<String> arns) {
        if (arn == null || !arn.isString())
            throw new IllegalStateException("Worker submission has no task ARN.");
        workers.requireTaskArn(arn.asString());
        arns.add(arn.asString());
    }

    record SubmissionHistory(Set<String> taskArns, boolean complete) {}
}
