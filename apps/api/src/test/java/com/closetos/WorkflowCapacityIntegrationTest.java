package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.closetos.activity.application.WorkflowSlots;
import com.closetos.media.api.WorkflowCapacityUnavailable;
import com.closetos.media.api.WorkflowJob;
import com.closetos.platform.api.OutboxAccess;
import com.closetos.platform.api.OutboxQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class WorkflowCapacityIntegrationTest extends PostgresIntegrationTest {
    private static final String MACHINE =
            "arn:aws:states:eu-west-2:123456789012:stateMachine:closetos-dev-media";
    @Autowired WorkflowSlots firstReplica;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OutboxAccess outbox;
    @Autowired OutboxQueue queue;
    private WorkflowSlots secondReplica;

    @BeforeEach
    void cleanReservationsAndCreateAnIndependentDispatcherInstance() {
        jdbc.sql("DELETE FROM workflow_slot").update();
        jdbc.sql("UPDATE workflow_capacity SET maximum_active = 4 WHERE id = 1").update();
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(new WorkflowSlots(jdbc, json, new SimpleMeterRegistry()));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        secondReplica = (WorkflowSlots) proxy.getProxy();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM workflow_slot").update();
        jdbc.sql("UPDATE workflow_capacity SET maximum_active = 4 WHERE id = 1").update();
        jdbc.sql("DELETE FROM outbox_event WHERE idempotency_key LIKE 'slot-test:%'").update();
    }

    @Test
    void independentReplicasCannotRacePastTheGlobalDatabaseLimit() throws Exception {
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                var replica = i % 2 == 0 ? firstReplica : secondReplica;
                attempts.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                    try {
                                        replica.reserve(job(), MACHINE);
                                        return true;
                                    } catch (WorkflowCapacityUnavailable exception) {
                                        return false;
                                    }
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int admitted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(4);
            assertThat(firstReplica.count()).isEqualTo(4);
            assertThat(secondReplica.count()).isEqualTo(4);
            assertThat(firstReplica.pending())
                    .extracting(WorkflowSlots.Slot::jobId)
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void replayReusesItsExistingReservationEvenWhenTheGlobalLimitIsFull() {
        var first = job();
        firstReplica.reserve(first, MACHINE);
        for (int i = 0; i < 3; i++) firstReplica.reserve(job(), MACHINE);
        secondReplica.reserve(first, MACHINE);
        assertThat(firstReplica.count()).isEqualTo(4);
        assertThatThrownBy(() -> secondReplica.reserve(job(), MACHINE))
                .isInstanceOf(WorkflowCapacityUnavailable.class);
        assertThatThrownBy(() -> secondReplica.reserve(first, MACHINE + "-other"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startAttemptsSurviveACallerRollbackAndCannotBeAbandonedAsUnused() {
        var job = job();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        transaction -> {
                            firstReplica.reserve(job, MACHINE);
                            assertThat(firstReplica.beginAttempt(job.jobId(), arn(job))).isTrue();
                            transaction.setRollbackOnly();
                        });
        secondReplica.abandon(job.jobId());
        assertThat(firstReplica.pending())
                .singleElement()
                .satisfies(slot -> assertThat(slot.attempted()).isTrue());
        assertThat(secondReplica.beginAttempt(UUID.randomUUID(), arn(job))).isFalse();
        assertThat(secondReplica.beginAttempt(job.jobId(), arn(job) + "-other")).isFalse();
    }

    @Test
    void unattemptedReservationsCanBeAbandonedAndReallocated() {
        var job = job();
        firstReplica.reserve(job, MACHINE);
        secondReplica.abandon(job.jobId());
        assertThat(firstReplica.count()).isZero();
        assertThat(firstReplica.beginAttempt(job.jobId(), arn(job))).isFalse();
        firstReplica.reserve(job(), MACHINE);
        assertThat(secondReplica.count()).isEqualTo(1);
    }

    @Test
    void loweringTheSharedLimitStopsNewAdmissionsUntilExistingWorkFinishes() {
        var one = job();
        var two = job();
        firstReplica.reserve(one, MACHINE);
        firstReplica.reserve(two, MACHINE);
        jdbc.sql("UPDATE workflow_capacity SET maximum_active = 1 WHERE id = 1").update();
        assertThatThrownBy(() -> secondReplica.reserve(job(), MACHINE))
                .isInstanceOf(WorkflowCapacityUnavailable.class);
        firstReplica.finished(one.jobId());
        assertThatThrownBy(() -> secondReplica.reserve(job(), MACHINE))
                .isInstanceOf(WorkflowCapacityUnavailable.class);
        firstReplica.finished(two.jobId());
        secondReplica.reserve(job(), MACHINE);
        assertThat(firstReplica.count()).isEqualTo(1);
    }

    @Test
    void capacityDeferralDoesNotConsumeTheOutboxFailureBudget() {
        var job = job();
        String key = "slot-test:" + job.jobId();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        transaction ->
                                outbox.enqueue(
                                        "image", job.imageId(), "START_PROCESSING", key, job));
        for (int attempt = 0; attempt < 8; attempt++) {
            var event = queue.claim(Set.of("START_PROCESSING")).getFirst();
            assertThat(event.publishAttempts()).isEqualTo(1);
            queue.defer(event, Duration.ZERO);
        }
        assertThat(
                        jdbc.sql(
                                        "SELECT publish_attempts FROM outbox_event WHERE idempotency_key=:key")
                                .param("key", key)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private String arn(WorkflowJob job) {
        return MACHINE.replace(":stateMachine:", ":execution:") + ":" + job.executionName();
    }

    private WorkflowJob job() {
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
                Base64.getEncoder().encodeToString(new byte[32]),
                "1-r1",
                UUID.randomUUID().toString());
    }
}
