package com.closetos.activity.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;
import tools.jackson.databind.json.JsonMapper;

class MediaExecutionHistoryTest {
    private static final String EXECUTION =
            "arn:aws:states:eu-west-2:123456789012:execution:closetos-dev-media:job";
    private static final String TASK =
            "arn:aws:ecs:eu-west-2:123456789012:task/closetos-dev/0123456789abcdef0123456789abcdef";
    private final SfnClient sfn = mock(SfnClient.class);
    private final MediaExecutionHistory history =
            new MediaExecutionHistory(
                    sfn,
                    JsonMapper.builder().build(),
                    new AwsMediaTasks(
                            mock(EcsClient.class),
                            "arn:aws:ecs:eu-west-2:123456789012:cluster/closetos-dev"));

    private HistoryEvent event(long id, String type, long previous) {
        return HistoryEvent.builder().id(id).type(type).previousEventId(previous).build();
    }

    private HistoryEvent scheduled() {
        return event(2, "TaskScheduled", 1).toBuilder()
                .taskScheduledEventDetails(
                        TaskScheduledEventDetails.builder()
                                .resourceType("ecs")
                                .resource("runTask.sync")
                                .build())
                .build();
    }

    private HistoryEvent submitted(String output) {
        return event(4, "TaskSubmitted", 3).toBuilder()
                .taskSubmittedEventDetails(
                        TaskSubmittedEventDetails.builder()
                                .resourceType("ecs")
                                .resource("runTask.sync")
                                .output(output)
                                .build())
                .build();
    }

    private void page(HistoryEvent... events) {
        when(sfn.getExecutionHistory(any(GetExecutionHistoryRequest.class)))
                .thenReturn(GetExecutionHistoryResponse.builder().events(events).build());
    }

    private void completed(String output) {
        page(
                event(1, "ExecutionStarted", 0),
                scheduled(),
                event(3, "TaskStarted", 2),
                submitted(output),
                event(5, "ExecutionFailed", 4));
    }

    @Test
    void capturesWorkerHandlesAcrossHistoryPagesAndCorrelatesThemWithTheScheduledCall() {
        when(sfn.getExecutionHistory(any(GetExecutionHistoryRequest.class)))
                .thenReturn(
                        GetExecutionHistoryResponse.builder()
                                .events(
                                        event(1, "ExecutionStarted", 0),
                                        scheduled(),
                                        event(3, "TaskStarted", 2))
                                .nextToken("second")
                                .build(),
                        GetExecutionHistoryResponse.builder()
                                .events(
                                        submitted(
                                                "{\"Tasks\":[{\"TaskArn\":\""
                                                        + TASK
                                                        + "\"}],\"Failures\":[]}"),
                                        event(5, "ExecutionFailed", 4))
                                .build());
        var result = history.inspect(EXECUTION, ExecutionStatus.FAILED);
        assertThat(result.complete()).isTrue();
        assertThat(result.taskArns()).containsExactly(TASK);
        verify(sfn, times(2))
                .getExecutionHistory(
                        argThat(
                                (GetExecutionHistoryRequest request) ->
                                        request.executionArn().equals(EXECUTION)
                                                && request.includeExecutionData()
                                                && !request.reverseOrder()
                                                && request.maxResults() == 1000));
    }

    @Test
    void aTerminalExecutionWithAnAmbiguousLaunchCannotConfirmNoRemainingWorkers() {
        page(
                event(1, "ExecutionStarted", 0),
                scheduled(),
                event(3, "TaskTimedOut", 2),
                event(4, "ExecutionTimedOut", 3));
        var result = history.inspect(EXECUTION, ExecutionStatus.TIMED_OUT);
        assertThat(result.complete()).isFalse();
        assertThat(result.taskArns()).isEmpty();
    }

    @Test
    void anInputRejectedBeforeRunTaskHasNoWorkerSubmissionsToDrain() {
        page(
                event(1, "ExecutionStarted", 0),
                event(2, "FailStateEntered", 1),
                event(3, "ExecutionFailed", 2));
        assertThat(history.inspect(EXECUTION, ExecutionStatus.FAILED).complete()).isTrue();
    }

    @Test
    void capturesAnIndividualTaskArnResponse() {
        completed("{\"TaskArn\":\"" + TASK + "\"}");
        assertThat(history.inspect(EXECUTION, ExecutionStatus.FAILED).taskArns())
                .containsExactly(TASK);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"Tasks\":[]}",
                "{\"Tasks\":[{}]}",
                "{\"TaskArn\":123}",
                "not-json",
                "{\"Tasks\":[{\"TaskArn\":\"foreign\"}]}",
                "{\"TaskArn\":\"foreign\",\"Failures\":[{}]}"
            })
    void missingMalformedOrForeignWorkerHandlesCannotBeTreatedAsNoWorkers(String output) {
        completed(output);
        assertThatThrownBy(() -> history.inspect(EXECUTION, ExecutionStatus.FAILED))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void missingEventsOrATerminalStatusMismatchCannotPermitCleanup() {
        page(event(1, "ExecutionStarted", 0), event(3, "ExecutionFailed", 1));
        assertThatThrownBy(() -> history.inspect(EXECUTION, ExecutionStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        page(event(1, "ExecutionStarted", 0), event(2, "ExecutionSucceeded", 1));
        assertThatThrownBy(() -> history.inspect(EXECUTION, ExecutionStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        page();
        assertThatThrownBy(() -> history.inspect(EXECUTION, ExecutionStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void truncatedOrUnlinkedSubmissionsCannotPermitCleanup() {
        var submission = submitted("{\"TaskArn\":\"" + TASK + "\"}");
        page(
                event(1, "ExecutionStarted", 0),
                scheduled(),
                event(3, "TaskStarted", 2),
                submission.toBuilder()
                        .taskSubmittedEventDetails(
                                submission.taskSubmittedEventDetails().toBuilder()
                                        .outputDetails(
                                                HistoryEventExecutionDataDetails.builder()
                                                        .truncated(true)
                                                        .build())
                                        .build())
                        .build(),
                event(5, "ExecutionFailed", 4));
        assertThatThrownBy(() -> history.inspect(EXECUTION, ExecutionStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        page(
                event(1, "ExecutionStarted", 0),
                scheduled(),
                event(3, "TaskStarted", 2),
                submission.toBuilder().previousEventId(1L).build(),
                event(5, "ExecutionFailed", 4));
        assertThatThrownBy(() -> history.inspect(EXECUTION, ExecutionStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theFirstEventMayOmitItsOptionalParentId() {
        page(
                event(1, "ExecutionStarted", 0).toBuilder().previousEventId((Long) null).build(),
                event(2, "ExecutionFailed", 1));
        assertThat(history.inspect(EXECUTION, ExecutionStatus.FAILED).complete()).isTrue();
    }

    @Test
    void everyRetrySubmissionMustRetainItsOwnWorkerHandle() {
        String second =
                TASK.replace(
                        "0123456789abcdef0123456789abcdef", "abcdef0123456789abcdef0123456789");
        page(
                event(1, "ExecutionStarted", 0),
                scheduled(),
                event(3, "TaskStarted", 2),
                submitted("{\"TaskArn\":\"" + TASK + "\"}"),
                event(5, "TaskFailed", 4),
                scheduled().toBuilder().id(6L).previousEventId(5L).build(),
                event(7, "TaskStarted", 6),
                submitted("{\"TaskArn\":\"" + second + "\"}").toBuilder()
                        .id(8L)
                        .previousEventId(7L)
                        .build(),
                event(9, "ExecutionFailed", 8));
        var result = history.inspect(EXECUTION, ExecutionStatus.FAILED);
        assertThat(result.complete()).isTrue();
        assertThat(result.taskArns()).containsExactlyInAnyOrder(TASK, second);
    }

    @Test
    void repeatPaginationCannotPermitCleanup() {
        when(sfn.getExecutionHistory(any(GetExecutionHistoryRequest.class)))
                .thenReturn(GetExecutionHistoryResponse.builder().nextToken("repeat").build());
        assertThatThrownBy(() -> history.inspect(EXECUTION, ExecutionStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
    }
}
