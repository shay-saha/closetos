package com.closetos.media.application;

import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.ProcessingResult;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.media.api.WorkflowJob;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.ExpensiveAction;
import com.closetos.platform.api.ExpensiveActionLimits;
import com.closetos.platform.api.OutboxAccess;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProcessingRegistry implements ProcessingAccess {
    private final JdbcClient jdbc;
    private final MediaAccess media;
    private final OutboxAccess outbox;
    private final JsonMapper json;
    private final ExpensiveActionLimits limits;

    public ProcessingRegistry(
            JdbcClient jdbc,
            MediaAccess media,
            OutboxAccess outbox,
            JsonMapper json,
            ExpensiveActionLimits limits) {
        this.jdbc = jdbc;
        this.media = media;
        this.outbox = outbox;
        this.json = json;
        this.limits = limits;
    }

    @Override
    @Transactional
    public Optional<WorkflowJob> uploaded(ImageRecord image, String eventId) {
        lockImage(image.id());
        if (!newEvent(eventId, "IMAGE_UPLOADED")) return Optional.empty();
        var current = media.ownedImage(image.id(), image.wardrobeId());
        if (current.processingStatus() != ProcessingStatus.AWAITING_UPLOAD) return Optional.empty();
        setState(image.id(), ProcessingStatus.UPLOADED);
        return Optional.of(createJob(current, 1));
    }

    @Override
    @Transactional
    public WorkflowJob retry(UUID id, UUID wardrobe) {
        lockImage(id);
        ImageRecord image = media.ownedImage(id, wardrobe);
        var status = snapshot(id, wardrobe);
        if (!status.canRetry())
            throw DomainException.invalid("This photograph cannot be retried right now.");
        limits.consume(wardrobe, ExpensiveAction.PROCESSING_RETRY);
        setState(id, ProcessingStatus.UPLOADED);
        return createJob(image, status.attemptCount() + 1);
    }

    private WorkflowJob createJob(ImageRecord image, int attempt) {
        UUID jobId = UUID.randomUUID();
        String pipeline = "1-r" + attempt;
        WorkflowJob job =
                new WorkflowJob(
                        jobId,
                        image.id(),
                        image.garmentId(),
                        image.wardrobeId(),
                        image.sourceS3Key(),
                        image.prefix() + "pipelines/" + pipeline + "/",
                        image.mimeType(),
                        image.expectedSize(),
                        image.sourceChecksum(),
                        pipeline,
                        UUID.randomUUID().toString());
        jdbc.sql(
                        """
                INSERT INTO processing_job (id, image_id, pipeline_version, state, attempt_count, analysis_status)
                VALUES (:id, :image, :pipeline, 'UPLOADED', :attempt, 'PENDING')
                """)
                .param("id", jobId)
                .param("image", image.id())
                .param("pipeline", pipeline)
                .param("attempt", attempt)
                .update();
        outbox.enqueue(
                image.wardrobeId(),
                "image",
                image.id(),
                "START_PROCESSING",
                "process:" + jobId,
                job);
        return job;
    }

    @Override
    public Optional<WorkflowJob> context(UUID id) {
        return jdbc.sql(
                        """
                SELECT j.id AS job_id, i.id AS image_id, i.garment_id, i.wardrobe_id, i.source_s3_key AS source_key,
                    substring(i.source_s3_key FROM 1 FOR length(i.source_s3_key) - length(split_part(i.source_s3_key, '/', -1)))
                        || 'pipelines/' || j.pipeline_version || '/' AS output_prefix,
                    i.mime_type, i.expected_size, i.source_checksum AS checksum_sha256, j.pipeline_version,
                    j.id::text AS request_id
                FROM processing_job j JOIN garment_image i ON i.id = j.image_id WHERE j.id = :id
                """)
                .param("id", id)
                .query(WorkflowJob.class)
                .optional();
    }

    @Override
    public boolean runnable(UUID id) {
        return jdbc.sql(
                        """
                SELECT EXISTS (SELECT 1 FROM processing_job j
                    WHERE j.id = :id AND j.state IN ('UPLOADED', 'PROCESSING_MEDIA', 'ANALYSING')
                    AND NOT EXISTS (SELECT 1 FROM processing_job newer
                        WHERE newer.image_id = j.image_id AND newer.attempt_count > j.attempt_count))
                """)
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    @Override
    public ProcessingSnapshot snapshot(UUID id, UUID wardrobe) {
        ImageRecord image = media.ownedImage(id, wardrobe);
        var latest =
                jdbc.sql(
                                "SELECT state, attempt_count, failure_code, failure_detail, analysis_status, analysis_failure FROM processing_job WHERE image_id = :id ORDER BY attempt_count DESC LIMIT 1")
                        .param("id", id)
                        .query(JobState.class)
                        .optional();
        return new ProcessingSnapshot(
                id,
                image.garmentId(),
                image.processingStatus(),
                latest.map(JobState::attemptCount).orElse(0),
                latest.map(JobState::failureCode).orElse(null),
                latest.map(JobState::failureDetail).orElse(null),
                image.processingStatus() == ProcessingStatus.FAILED
                        && latest.map(job -> job.attemptCount() < 5).orElse(false),
                latest.map(JobState::analysisStatus).orElse("NOT_REQUESTED"),
                latest.map(JobState::analysisFailure).orElse(null));
    }

    @Override
    @Transactional
    public boolean started(WorkflowJob job, String arn) {
        lockImage(job.imageId());
        UUID latest =
                jdbc.sql(
                                "SELECT id FROM processing_job WHERE image_id = :image ORDER BY attempt_count DESC LIMIT 1")
                        .param("image", job.imageId())
                        .query(UUID.class)
                        .optional()
                        .orElse(null);
        if (!job.jobId().equals(latest)) return false;
        int changed =
                jdbc.sql(
                                "UPDATE processing_job SET state = 'PROCESSING_MEDIA', execution_arn = :arn WHERE id = :id AND state IN ('UPLOADED', 'PROCESSING_MEDIA')")
                        .param("id", job.jobId())
                        .param("arn", arn)
                        .update();
        if (changed == 0) return false;
        jdbc.sql(
                        "UPDATE garment_image SET processing_status = 'PROCESSING_MEDIA', updated_at = now() WHERE id = :id AND processing_status IN ('UPLOADED', 'PROCESSING_MEDIA')")
                .param("id", job.imageId())
                .update();
        return true;
    }

    @Override
    @Transactional
    public boolean complete(ProcessingResult result, String eventId) {
        Optional<WorkflowJob> context = context(result.jobId());
        if (context.isEmpty()) return false;
        WorkflowJob job = context.get();
        lockImage(job.imageId());
        if (!newEvent(eventId, "PROCESSING_COMPLETED")) return false;
        UUID latest =
                jdbc.sql(
                                "SELECT id FROM processing_job WHERE image_id = :image ORDER BY attempt_count DESC LIMIT 1")
                        .param("image", job.imageId())
                        .query(UUID.class)
                        .single();
        if (!latest.equals(job.jobId())) return false;
        var analysis = AnalysisOutcome.from(result);
        int updated =
                jdbc.sql(
                                "UPDATE processing_job SET state = 'READY_FOR_REVIEW', completed_at = now(), analysis_status = :analysis, analysis_failure = :analysisFailure WHERE id = :id AND state IN ('UPLOADED', 'PROCESSING_MEDIA', 'ANALYSING')")
                        .param("id", job.jobId())
                        .param("analysis", analysis.status())
                        .param("analysisFailure", analysis.failure())
                        .update();
        if (updated == 0) return false;
        var isolated = result.assets().get("isolated");
        jdbc.sql(
                        """
                UPDATE garment_image SET processing_status = 'READY_FOR_REVIEW', assets = CAST(:assets AS jsonb),
                    width = :width, height = :height, updated_at = now() WHERE id = :id
                """)
                .param("id", job.imageId())
                .param("assets", json.writeValueAsString(result.assets()))
                .param("width", isolated.width())
                .param("height", isolated.height())
                .update();
        ImageRecord image = media.ownedImage(job.imageId(), job.wardrobeId());
        if (image.deleteOriginalAfterIsolation())
            outbox.enqueue(
                    image.wardrobeId(),
                    "image",
                    image.id(),
                    "DELETE_ORIGINAL",
                    "delete-original:" + image.id(),
                    Map.of("imageId", image.id(), "sourceKey", image.sourceS3Key()));
        return true;
    }

    @Override
    @Transactional
    public void fail(UUID id, String code, String detail) {
        var context = context(id);
        if (context.isEmpty()) return;
        WorkflowJob job = context.get();
        lockImage(job.imageId());
        UUID latest =
                jdbc.sql(
                                "SELECT id FROM processing_job WHERE image_id = :id ORDER BY attempt_count DESC LIMIT 1")
                        .param("id", job.imageId())
                        .query(UUID.class)
                        .single();
        if (!latest.equals(id)) return;
        int updated =
                jdbc.sql(
                                """
                UPDATE processing_job SET state = 'FAILED', failure_code = :code, failure_detail = :detail,
                    completed_at = now() WHERE id = :id AND state IN ('UPLOADED', 'PROCESSING_MEDIA', 'ANALYSING')
                """)
                        .param("id", id)
                        .param("code", code)
                        .param("detail", detail)
                        .update();
        if (updated > 0) setState(job.imageId(), ProcessingStatus.FAILED);
    }

    @Override
    @Transactional
    public void originalDeleted(UUID imageId) {
        jdbc.sql("UPDATE garment_image SET original_deleted = true WHERE id = :id")
                .param("id", imageId)
                .update();
    }

    private void setState(UUID id, ProcessingStatus status) {
        jdbc.sql(
                        "UPDATE garment_image SET processing_status = :status, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .update();
    }

    private void lockImage(UUID id) {
        jdbc.sql("SELECT id FROM garment_image WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional();
    }

    private boolean newEvent(String id, String type) {
        if (id == null || id.isBlank() || id.length() > 200)
            throw DomainException.invalid("Invalid event identifier.");
        return jdbc.sql(
                                "INSERT INTO processed_integration_event (event_id, event_type) VALUES (:id, :type) ON CONFLICT DO NOTHING")
                        .param("id", id)
                        .param("type", type)
                        .update()
                == 1;
    }

    private record AnalysisOutcome(String status, String failure) {
        static AnalysisOutcome from(ProcessingResult result) {
            if (result.analysisKey() != null) return new AnalysisOutcome("READY", null);
            if ("ANALYSIS_NOT_CONFIGURED".equals(result.analysisFailure()))
                return new AnalysisOutcome("NOT_CONFIGURED", "ANALYSIS_NOT_CONFIGURED");
            String failure = result.analysisFailure();
            if (failure == null
                    || !Set.of("ANALYSIS_UNAVAILABLE", "INVALID_ANALYSIS_OUTPUT").contains(failure))
                failure = "ANALYSIS_UNAVAILABLE";
            return new AnalysisOutcome("FAILED", failure);
        }
    }

    private record JobState(
            String state,
            int attemptCount,
            String failureCode,
            String failureDetail,
            String analysisStatus,
            String analysisFailure) {}
}
