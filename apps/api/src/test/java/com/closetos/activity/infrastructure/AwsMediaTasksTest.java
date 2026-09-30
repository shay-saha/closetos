package com.closetos.activity.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

class AwsMediaTasksTest {
    private static final String CLUSTER = "arn:aws:ecs:eu-west-2:123456789012:cluster/closetos-dev";
    private static final String TASK =
            "arn:aws:ecs:eu-west-2:123456789012:task/closetos-dev/0123456789abcdef0123456789abcdef";
    private final UUID job = UUID.randomUUID();
    private final EcsClient ecs = mock(EcsClient.class);
    private final AwsMediaTasks workers = new AwsMediaTasks(ecs, CLUSTER);

    private Task.Builder task(String status) {
        return Task.builder()
                .taskArn(TASK)
                .clusterArn(CLUSTER)
                .taskDefinitionArn(
                        "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-media:4")
                .startedBy(job.toString())
                .group("family:closetos-dev-media")
                .launchType(LaunchType.FARGATE)
                .lastStatus(status)
                .desiredStatus("RUNNING")
                .tags(
                        Tag.builder().key("Application").value("closetos").build(),
                        Tag.builder().key("Environment").value("dev").build(),
                        Tag.builder().key("ClosetosWorker").value("media").build(),
                        Tag.builder().key("ClosetosJob").value(job.toString()).build());
    }

    private void response(Task... tasks) {
        when(ecs.describeTasks(any(DescribeTasksRequest.class)))
                .thenReturn(DescribeTasksResponse.builder().tasks(tasks).build());
    }

    @Test
    void discoveryFollowsPagesWithoutCombiningStartedByWithOtherFilters() {
        String second =
                TASK.replace(
                        "0123456789abcdef0123456789abcdef", "abcdef0123456789abcdef0123456789");
        when(ecs.listTasks(any(ListTasksRequest.class)))
                .thenReturn(
                        ListTasksResponse.builder().taskArns(TASK).nextToken("page-2").build(),
                        ListTasksResponse.builder().taskArns(TASK, second).build());
        assertThat(workers.discover(job)).containsExactlyInAnyOrder(TASK, second);
        verify(ecs, times(2))
                .listTasks(
                        argThat(
                                (ListTasksRequest request) ->
                                        request.cluster().equals(CLUSTER)
                                                && request.startedBy().equals(job.toString())
                                                && request.desiredStatus() == null
                                                && request.family() == null
                                                && request.serviceName() == null));
    }

    @Test
    void emptyDiscoveryDoesNotAssertThatPreviouslyObservedWorkersHaveStopped() {
        when(ecs.listTasks(any(ListTasksRequest.class)))
                .thenReturn(ListTasksResponse.builder().build());
        assertThat(workers.discover(job)).isEmpty();
        response(task("RUNNING").build());
        assertThat(workers.inspect(job, Set.of(TASK), false)).containsEntry(TASK, false);
        verify(ecs, never()).stopTask(any(StopTaskRequest.class));
    }

    @Test
    void aStopAcknowledgementCannotSubstituteForALaterStoppedObservation() {
        response(task("RUNNING").build());
        when(ecs.stopTask(any(StopTaskRequest.class)))
                .thenReturn(StopTaskResponse.builder().task(task("STOPPED").build()).build());
        assertThat(workers.inspect(job, Set.of(TASK), true)).containsEntry(TASK, false);
        verify(ecs)
                .stopTask(
                        argThat(
                                (StopTaskRequest request) ->
                                        request.cluster().equals(CLUSTER)
                                                && request.task().equals(TASK)));
        response(task("STOPPED").build());
        assertThat(workers.inspect(job, Set.of(TASK), true)).containsEntry(TASK, true);
        verify(ecs, times(1)).stopTask(any(StopTaskRequest.class));
        verify(ecs, times(2))
                .describeTasks(
                        argThat(
                                (DescribeTasksRequest request) ->
                                        request.cluster().equals(CLUSTER)
                                                && request.tasks().equals(List.of(TASK))
                                                && request.includeAsStrings()
                                                        .equals(List.of("TAGS"))));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PENDING",
                "PROVISIONING",
                "RUNNING",
                "STOPPING",
                "DEPROVISIONING",
                "FUTURE_STATE"
            })
    void onlyTheStoppedLastStatusConfirmsTermination(String status) {
        response(task(status).desiredStatus("STOPPED").build());
        assertThat(workers.inspect(job, Set.of(TASK), true)).containsEntry(TASK, false);
        verify(ecs, never()).stopTask(any(StopTaskRequest.class));
    }

    @Test
    void invalidOwnershipOrTagsCannotCancelAnyTask() {
        List<Consumer<Task.Builder>> invalid =
                List.of(
                        builder -> builder.clusterArn(CLUSTER.replace("-dev", "-prod")),
                        builder -> builder.taskArn(TASK.replace("123456789012", "999999999999")),
                        builder ->
                                builder.taskDefinitionArn(
                                        "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-api:1"),
                        builder -> builder.taskDefinitionArn(null),
                        builder -> builder.startedBy(UUID.randomUUID().toString()),
                        builder -> builder.group("service:closetos-dev-api"),
                        builder -> builder.launchType(LaunchType.EC2),
                        builder -> builder.tags(List.of()),
                        builder ->
                                builder.tags(
                                        Tag.builder().key("ClosetosWorker").value("api").build()),
                        builder ->
                                builder.tags(
                                        Tag.builder().key("ClosetosWorker").value("media").build(),
                                        Tag.builder().key("ClosetosWorker").value("api").build()),
                        builder -> builder.lastStatus(null));
        for (var mutation : invalid) {
            var builder = task("RUNNING");
            mutation.accept(builder);
            response(builder.build());
            assertThatThrownBy(() -> workers.inspect(job, Set.of(TASK), true))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(ecs, never()).stopTask(any(StopTaskRequest.class));
    }

    @Test
    void partialMissingDuplicatedOrForeignResponsesCannotConfirmOrCancelWorkers() {
        response();
        assertThatThrownBy(() -> workers.inspect(job, Set.of(TASK), true))
                .isInstanceOf(IllegalStateException.class);
        response(task("RUNNING").build(), task("RUNNING").build());
        assertThatThrownBy(() -> workers.inspect(job, Set.of(TASK), true))
                .isInstanceOf(IllegalStateException.class);
        response(
                task("RUNNING")
                        .taskArn(
                                TASK.replace(
                                        "0123456789abcdef0123456789abcdef",
                                        "abcdef0123456789abcdef0123456789"))
                        .build());
        assertThatThrownBy(() -> workers.inspect(job, Set.of(TASK), true))
                .isInstanceOf(IllegalStateException.class);
        when(ecs.describeTasks(any(DescribeTasksRequest.class)))
                .thenReturn(
                        DescribeTasksResponse.builder()
                                .tasks(task("RUNNING").build())
                                .failures(Failure.builder().arn(TASK).reason("MISSING").build())
                                .build());
        assertThatThrownBy(() -> workers.inspect(job, Set.of(TASK), true))
                .isInstanceOf(IllegalStateException.class);
        verify(ecs, never()).stopTask(any(StopTaskRequest.class));
    }

    @Test
    void invalidInputIsRejectedBeforeCallingAws() {
        assertThatThrownBy(
                        () -> workers.inspect(job, Set.of(TASK.replace("-dev/", "-prod/")), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(workers.inspect(job, Set.of(), true)).isEmpty();
        assertThatThrownBy(() -> new AwsMediaTasks(ecs, ""))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ecs);
    }

    @Test
    void malformedOrRepeatedDiscoveryPagesCannotLookLikeACompleteEmptyResult() {
        when(ecs.listTasks(any(ListTasksRequest.class)))
                .thenReturn(ListTasksResponse.builder().nextToken("repeat").build());
        assertThatThrownBy(() -> workers.discover(job)).isInstanceOf(IllegalStateException.class);
        when(ecs.listTasks(any(ListTasksRequest.class)))
                .thenReturn(ListTasksResponse.builder().taskArns("wrong-cluster").build());
        assertThatThrownBy(() -> workers.discover(job)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unavailableAwsCallsPropagateInsteadOfInventingStopConfirmations() {
        when(ecs.describeTasks(any(DescribeTasksRequest.class)))
                .thenThrow(SdkClientException.create("Unavailable"));
        assertThatThrownBy(() -> workers.inspect(job, Set.of(TASK), true))
                .isInstanceOf(SdkClientException.class);
        verify(ecs, never()).stopTask(any(StopTaskRequest.class));
    }
}
