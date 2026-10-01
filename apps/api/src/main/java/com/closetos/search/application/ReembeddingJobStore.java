package com.closetos.search.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxAccess;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.ReembeddingJob;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReembeddingJobStore {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final GarmentAccess garments;
    private final OutboxAccess outbox;
    private final EmbeddingModels models;

    public ReembeddingJobStore(
            JdbcClient jdbc,
            Clock clock,
            GarmentAccess garments,
            OutboxAccess outbox,
            EmbeddingModels models) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.garments = garments;
        this.outbox = outbox;
        this.models = models;
    }

    @Transactional
    public Optional<UUID> existing(String requester, UUID key, UUID wardrobe) {
        var existing =
                jdbc.sql(
                                "SELECT id, wardrobe_id FROM reembedding_job WHERE requested_by = :requester AND request_key = :key")
                        .param("requester", requester)
                        .param("key", key)
                        .query(
                                (rs, row) ->
                                        new Scope(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("wardrobe_id", UUID.class)))
                        .optional();
        if (existing.isPresent() && !Objects.equals(existing.get().wardrobeId(), wardrobe))
            throw DomainException.conflict();
        return existing.map(Scope::id);
    }

    @Transactional
    public UUID create(String requester, UUID key, UUID wardrobe, ModelInfo model) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))")
                .param("scope", requester + ":reembedding:" + key)
                .query()
                .singleRow();
        var repeated = existing(requester, key, wardrobe);
        if (repeated.isPresent()) return repeated.get();
        models.register(model);
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO reembedding_job(id, requested_by, request_key, wardrobe_id, model_key, created_at) VALUES (:id, :requester, :key, :wardrobe, :model, :created)")
                .param("id", id)
                .param("requester", requester)
                .param("key", key)
                .param("wardrobe", wardrobe)
                .param("model", model.modelKey())
                .param("created", Timestamp.from(clock.instant()))
                .update();
        for (var target : garments.embeddingTargets(wardrobe)) {
            String eventKey = "reembedding:" + id + ":" + target.garmentId();
            outbox.enqueue(
                    target.wardrobeId(),
                    "garment",
                    target.garmentId(),
                    "REBUILD_EMBEDDING",
                    eventKey,
                    Map.of("wardrobeId", target.wardrobeId(), "modelKey", model.modelKey()));
            jdbc.sql(
                            "INSERT INTO reembedding_item(job_id, event_id) SELECT :job, id FROM outbox_event WHERE idempotency_key = :key")
                    .param("job", id)
                    .param("key", eventKey)
                    .update();
        }
        return id;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReembeddingJob get(UUID id) {
        var details =
                jdbc.sql(
                                """
                SELECT j.wardrobe_id, j.created_at, m.provider, m.model_id, m.model_version, m.pipeline_version, m.dimensions
                FROM reembedding_job j JOIN embedding_model m ON m.model_key = j.model_key WHERE j.id = :id
                """)
                        .param("id", id)
                        .query(
                                (rs, row) ->
                                        new Details(
                                                rs.getObject("wardrobe_id", UUID.class),
                                                rs.getTimestamp("created_at").toInstant(),
                                                new EmbeddingModel(
                                                        rs.getString("provider"),
                                                        rs.getString("model_id"),
                                                        rs.getString("model_version"),
                                                        rs.getString("pipeline_version"),
                                                        rs.getInt("dimensions"))))
                        .optional()
                        .orElseThrow(() -> DomainException.notFound("Re-embedding job"));
        var counts =
                jdbc.sql(
                                """
                SELECT count(*) AS total,
                    count(*) FILTER (WHERE o.published_at IS NULL AND o.lease_until > :now) AS running,
                    count(*) FILTER (WHERE o.published_at IS NOT NULL AND i.outcome = 'SUCCEEDED') AS succeeded,
                    count(*) FILTER (WHERE o.published_at IS NOT NULL AND i.outcome = 'SKIPPED') AS skipped,
                    count(*) FILTER (WHERE o.published_at IS NOT NULL AND (i.outcome = 'FAILED' OR i.outcome IS NULL)) AS failed,
                    coalesce(sum(o.publish_attempts), 0) AS attempts, max(o.published_at) AS completed
                FROM reembedding_item i JOIN outbox_event o ON o.id = i.event_id WHERE i.job_id = :id
                """)
                        .param("id", id)
                        .param("now", Timestamp.from(clock.instant()))
                        .query(
                                (rs, row) ->
                                        new Counts(
                                                rs.getInt("total"),
                                                rs.getInt("running"),
                                                rs.getInt("succeeded"),
                                                rs.getInt("skipped"),
                                                rs.getInt("failed"),
                                                rs.getLong("attempts"),
                                                rs.getTimestamp("completed") == null
                                                        ? null
                                                        : rs.getTimestamp("completed").toInstant()))
                        .single();
        int queued =
                counts.total()
                        - counts.running()
                        - counts.succeeded()
                        - counts.skipped()
                        - counts.failed();
        boolean complete = queued == 0 && counts.running() == 0;
        String state =
                complete
                        ? counts.failed() == 0
                                ? "SUCCEEDED"
                                : counts.failed() == counts.total() ? "FAILED" : "PARTIAL_FAILURE"
                        : counts.attempts() == 0 ? "QUEUED" : "RUNNING";
        Map<String, Integer> failures = new LinkedHashMap<>();
        jdbc.sql(
                        """
                SELECT coalesce(i.failure_code, 'EMBEDDING_FAILURE') AS code, count(*) AS total
                FROM reembedding_item i JOIN outbox_event o ON o.id = i.event_id
                WHERE i.job_id = :id AND o.published_at IS NOT NULL AND (i.outcome = 'FAILED' OR i.outcome IS NULL)
                GROUP BY code ORDER BY code
                """)
                .param("id", id)
                .query((rs, row) -> Map.entry(rs.getString("code"), rs.getInt("total")))
                .list()
                .forEach(entry -> failures.put(entry.getKey(), entry.getValue()));
        return new ReembeddingJob(
                id,
                details.wardrobeId(),
                details.model(),
                state,
                counts.total(),
                queued,
                counts.running(),
                counts.succeeded(),
                counts.skipped(),
                counts.failed(),
                Map.copyOf(failures),
                details.createdAt(),
                complete ? counts.total() == 0 ? details.createdAt() : counts.completedAt() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ReembeddingJob> recent() {
        return jdbc
                .sql("SELECT id FROM reembedding_job ORDER BY created_at DESC, id LIMIT 20")
                .query(UUID.class)
                .list()
                .stream()
                .map(this::get)
                .toList();
    }

    record Scope(UUID id, UUID wardrobeId) {}

    record Details(UUID wardrobeId, Instant createdAt, EmbeddingModel model) {}

    record Counts(
            int total,
            int running,
            int succeeded,
            int skipped,
            int failed,
            long attempts,
            Instant completedAt) {}
}
