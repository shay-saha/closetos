package com.closetos.activity.infrastructure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskField;

@Component
@ConditionalOnProperty(name = "closetos.media.aws-consumer-enabled", havingValue = "true")
class AwsMediaTasks {
    private static final int MAX_TASKS = 32;
    private final EcsClient ecs;
    private final String cluster;
    private final String family;
    private final String application;
    private final String environment;
    private final Pattern taskArn;
    private final Pattern definitionArn;

    AwsMediaTasks(EcsClient ecs, @Value("${closetos.media.cluster-arn:}") String cluster) {
        var match =
                Pattern.compile(
                                "^(arn:aws(?:-[a-z]+)*:ecs:[a-z0-9-]+:[0-9]{12}:)cluster/(.+)-(dev|prod)$")
                        .matcher(cluster);
        if (!match.matches())
            throw new IllegalStateException("Configure the environment's media cluster ARN.");
        this.ecs = ecs;
        this.cluster = cluster;
        this.application = match.group(2);
        this.environment = match.group(3);
        String name = application + "-" + environment;
        this.family = name + "-media";
        this.taskArn =
                Pattern.compile(
                        Pattern.quote(match.group(1) + "task/" + name + "/") + "[a-f0-9]{32}");
        this.definitionArn =
                Pattern.compile(
                        Pattern.quote(match.group(1) + "task-definition/" + family + ":")
                                + "[1-9][0-9]*");
    }

    Set<String> discover(UUID job) {
        var arns = new LinkedHashSet<String>();
        var tokens = new HashSet<String>();
        String token = null;
        do {
            var response =
                    ecs.listTasks(
                            ListTasksRequest.builder()
                                    .cluster(cluster)
                                    .startedBy(job.toString())
                                    .maxResults(100)
                                    .nextToken(token)
                                    .build());
            for (String arn : response.taskArns()) {
                requireTaskArn(arn);
                arns.add(arn);
            }
            token = response.nextToken();
            if (arns.size() > MAX_TASKS
                    || (token != null && (!tokens.add(token) || tokens.size() >= 4)))
                throw new IllegalStateException(
                        "Media task discovery exceeded its bounded response.");
        } while (token != null);
        return Set.copyOf(arns);
    }

    Map<String, Boolean> inspect(UUID job, Set<String> arns, boolean stopActive) {
        if (arns.isEmpty()) return Map.of();
        if (arns.size() > MAX_TASKS)
            throw new IllegalStateException("Too many workers for a media job.");
        arns.forEach(this::requireTaskArn);
        var response =
                ecs.describeTasks(
                        DescribeTasksRequest.builder()
                                .cluster(cluster)
                                .tasks(arns)
                                .include(TaskField.TAGS)
                                .build());
        if (!response.failures().isEmpty())
            throw new IllegalStateException("Media worker status is unavailable.");
        var observed = new HashMap<String, Task>();
        for (Task task : response.tasks()) {
            requireOwnedTask(job, task);
            if (!arns.contains(task.taskArn()) || observed.put(task.taskArn(), task) != null)
                throw new IllegalStateException(
                        "Media worker response does not match the requested tasks.");
        }
        if (!observed.keySet().equals(arns))
            throw new IllegalStateException("Media worker response is incomplete.");
        var stopped = new HashMap<String, Boolean>();
        // Validate the entire response before performing any cancellation.
        for (Task task : observed.values()) {
            boolean confirmed = "STOPPED".equals(task.lastStatus());
            stopped.put(task.taskArn(), confirmed);
            if (stopActive && !confirmed && !"STOPPED".equals(task.desiredStatus())) {
                ecs.stopTask(
                        StopTaskRequest.builder()
                                .cluster(cluster)
                                .task(task.taskArn())
                                .reason("Media execution has ended; stop remaining workers.")
                                .build());
                // A StopTask acknowledgement is not a stop confirmation.
            }
        }
        return Map.copyOf(stopped);
    }

    void requireTaskArn(String arn) {
        if (arn == null || !taskArn.matcher(arn).matches())
            throw new IllegalStateException("Worker task is outside the configured media cluster.");
    }

    private void requireOwnedTask(UUID job, Task task) {
        requireTaskArn(task.taskArn());
        var tags = new HashMap<String, String>();
        for (var tag : task.tags()) {
            if (tag.key() == null
                    || tag.value() == null
                    || tags.put(tag.key(), tag.value()) != null)
                throw new IllegalStateException("Worker tags are invalid.");
        }
        if (!cluster.equals(task.clusterArn())
                || task.taskDefinitionArn() == null
                || !definitionArn.matcher(task.taskDefinitionArn()).matches()
                || !job.toString().equals(task.startedBy())
                || !("family:" + family).equals(task.group())
                || !"FARGATE".equals(task.launchTypeAsString())
                || !application.equals(tags.get("Application"))
                || !environment.equals(tags.get("Environment"))
                || !"media".equals(tags.get("ClosetosWorker"))
                || !job.toString().equals(tags.get("ClosetosJob"))
                || task.lastStatus() == null
                || task.lastStatus().isBlank())
            throw new IllegalStateException("Worker identity does not match the media job.");
    }
}
