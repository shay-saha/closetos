package com.closetos.activity.application;

import com.closetos.media.api.WorkflowCapacityUnavailable;
import com.closetos.media.api.WorkflowJob;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkflowSlots {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MeterRegistry metrics;

    public WorkflowSlots(JdbcClient jdbc, JsonMapper json, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.metrics = metrics;
        Gauge.builder("processing.workflow.slots.active", this, slots -> slots.count())
                .register(metrics);
        Gauge.builder("processing.workflow.slots.limit", this, slots -> slots.limit())
                .register(metrics);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(WorkflowJob job, String stateMachine) {
        int maximum =
                jdbc.sql("SELECT maximum_active FROM workflow_capacity WHERE id = 1 FOR UPDATE")
                        .query(Integer.class)
                        .single();
        String arn =
                stateMachine.replace(":stateMachine:", ":execution:") + ":" + job.executionName();
        var existing =
                jdbc.sql("SELECT execution_arn FROM workflow_slot WHERE job_id = :id")
                        .param("id", job.jobId())
                        .query(String.class)
                        .optional();
        if (existing.isPresent()) {
            if (!existing.get().equals(arn))
                throw new IllegalStateException("A job cannot change its processing workflow.");
            return;
        }
        if (count() >= maximum) {
            metrics.counter("processing.workflow.admission.deferred").increment();
            throw new WorkflowCapacityUnavailable();
        }
        jdbc.sql(
                        """
                INSERT INTO workflow_slot (job_id, execution_arn, state_machine_arn, execution_name, payload)
                VALUES (:id, :arn, :machine, :name, CAST(:payload AS jsonb))
                """)
                .param("id", job.jobId())
                .param("arn", arn)
                .param("machine", stateMachine)
                .param("name", job.executionName())
                .param("payload", json.writeValueAsString(job))
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginAttempt(UUID job, String executionArn) {
        return jdbc.sql(
                                "UPDATE workflow_slot SET attempted = true WHERE job_id = :id AND execution_arn = :arn")
                        .param("id", job)
                        .param("arn", executionArn)
                        .update()
                == 1;
    }

    @Transactional
    public void abandon(UUID job) {
        jdbc.sql("DELETE FROM workflow_slot WHERE job_id = :id AND NOT attempted")
                .param("id", job)
                .update();
    }

    public List<Slot> pending() {
        return jdbc.sql(
                        """
                SELECT job_id, execution_arn, state_machine_arn, execution_name, payload::text, attempted
                FROM workflow_slot ORDER BY observed_at NULLS FIRST, reserved_at LIMIT 16
                """)
                .query(Slot.class)
                .list();
    }

    @Transactional
    public void observed(UUID job, String status) {
        jdbc.sql(
                        "UPDATE workflow_slot SET observed_at = now(), observed_status = :status WHERE job_id = :id")
                .param("id", job)
                .param("status", status)
                .update();
    }

    @Transactional
    public void rememberTasks(UUID job, Set<String> taskArns) {
        if (jdbc.sql("SELECT job_id FROM workflow_slot WHERE job_id = :id FOR UPDATE")
                .param("id", job)
                .query(UUID.class)
                .optional()
                .isEmpty()) return;
        for (String arn : taskArns) {
            jdbc.sql(
                            "INSERT INTO workflow_task (task_arn, job_id) VALUES (:arn, :job) ON CONFLICT DO NOTHING")
                    .param("arn", arn)
                    .param("job", job)
                    .update();
            UUID owner =
                    jdbc.sql("SELECT job_id FROM workflow_task WHERE task_arn = :arn")
                            .param("arn", arn)
                            .query(UUID.class)
                            .single();
            if (!owner.equals(job))
                throw new IllegalStateException("A worker cannot belong to another job.");
        }
    }

    public Set<String> unconfirmedTasks(UUID job) {
        return Set.copyOf(
                jdbc.sql(
                                "SELECT task_arn FROM workflow_task WHERE job_id = :id AND stopped_at IS NULL")
                        .param("id", job)
                        .query(String.class)
                        .list());
    }

    @Transactional
    public void taskStopped(UUID job, String taskArn) {
        jdbc.sql(
                        "UPDATE workflow_task SET stopped_at = now() WHERE job_id = :id AND task_arn = :arn AND stopped_at IS NULL")
                .param("id", job)
                .param("arn", taskArn)
                .update();
    }

    @Transactional
    public void finished(UUID job) {
        jdbc.sql(
                        """
                DELETE FROM workflow_slot WHERE job_id = :id
                AND NOT EXISTS (SELECT 1 FROM workflow_task WHERE job_id = :id AND stopped_at IS NULL)
                """)
                .param("id", job)
                .update();
    }

    public int count() {
        return jdbc.sql("SELECT count(*) FROM workflow_slot").query(Integer.class).single();
    }

    public int limit() {
        return jdbc.sql("SELECT maximum_active FROM workflow_capacity WHERE id = 1")
                .query(Integer.class)
                .single();
    }

    public record Slot(
            UUID jobId,
            String executionArn,
            String stateMachineArn,
            String executionName,
            String payload,
            boolean attempted) {}
}
