package com.closetos.search.application;

import com.closetos.platform.api.OutboxEntry;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.EmbeddingProviderPort.VectorResult;
import com.closetos.search.application.EmbeddingInputLoader.Material;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingWorkRegistry {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final EmbeddingInputLoader inputs;

    public EmbeddingWorkRegistry(JdbcClient jdbc, Clock clock, EmbeddingInputLoader inputs) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.inputs = inputs;
    }

    @Transactional
    public boolean begin(OutboxEntry event, Material material, ModelInfo info) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))")
                .param("scope", material.garmentId() + ":" + info.modelKey())
                .query()
                .singleRow();
        var model = info.model();
        jdbc.sql(
                        """
                INSERT INTO embedding_model(model_key, provider, model_id, model_version, pipeline_version, dimensions)
                VALUES (:key, :provider, :id, :version, :pipeline, :dimensions) ON CONFLICT (model_key) DO NOTHING
                """)
                .param("key", info.modelKey())
                .param("provider", model.provider())
                .param("id", model.modelId())
                .param("version", model.modelVersion())
                .param("pipeline", model.pipelineVersion())
                .param("dimensions", model.dimensions())
                .update();
        var existing =
                jdbc.sql(
                                "SELECT provider, model_id, model_version, pipeline_version, dimensions FROM embedding_model WHERE model_key = :key")
                        .param("key", info.modelKey())
                        .query(com.closetos.search.api.EmbeddingModel.class)
                        .single();
        if (!existing.equals(model))
            throw new IllegalStateException(
                    "An embedding model key was reused with a different identity.");
        var current =
                jdbc.sql(
                                "SELECT source_fingerprint FROM garment_embedding WHERE garment_id = :garment AND wardrobe_id = :wardrobe AND model_key = :key")
                        .param("garment", material.garmentId())
                        .param("wardrobe", material.wardrobeId())
                        .param("key", info.modelKey())
                        .query(String.class)
                        .optional();
        if (current.filter(material.fingerprint()::equals).isPresent()) return false;
        var busy =
                jdbc.sql(
                                "SELECT count(*) FROM garment_embedding_work WHERE garment_id = :garment AND model_key = :key AND state = 'RUNNING' AND lease_until > :now")
                        .param("garment", material.garmentId())
                        .param("key", info.modelKey())
                        .param("now", now())
                        .query(Integer.class)
                        .single();
        if (busy > 0) throw new Busy();
        jdbc.sql(
                        """
                INSERT INTO garment_embedding_work(garment_id, wardrobe_id, model_key, source_fingerprint, state,
                    lease_owner, lease_until, attempt_count, updated_at)
                VALUES (:garment, :wardrobe, :key, :fingerprint, 'RUNNING', :event, :until, :attempt, :now)
                ON CONFLICT (garment_id, model_key) DO UPDATE SET source_fingerprint = EXCLUDED.source_fingerprint,
                    state = 'RUNNING', lease_owner = EXCLUDED.lease_owner, lease_until = EXCLUDED.lease_until,
                    attempt_count = EXCLUDED.attempt_count, failure_code = NULL, updated_at = EXCLUDED.updated_at
                """)
                .param("garment", material.garmentId())
                .param("wardrobe", material.wardrobeId())
                .param("key", info.modelKey())
                .param("fingerprint", material.fingerprint())
                .param("event", event.id())
                .param("attempt", event.publishAttempts())
                .param("until", Timestamp.from(clock.instant().plusSeconds(120)))
                .param("now", now())
                .update();
        return true;
    }

    @Transactional
    public boolean complete(
            OutboxEntry event, Material material, ModelInfo info, VectorResult result) {
        var current = inputs.load(material.garmentId(), material.wardrobeId());
        if (current.isEmpty()) return true;
        var owner =
                jdbc.sql(
                                "SELECT lease_owner FROM garment_embedding_work WHERE garment_id = :garment AND model_key = :key FOR UPDATE")
                        .param("garment", material.garmentId())
                        .param("key", info.modelKey())
                        .query(UUID.class)
                        .optional();
        if (owner.isEmpty() || !Objects.equals(owner.get(), event.id())) return false;
        if (!current.get().fingerprint().equals(material.fingerprint())) {
            state(event, info.modelKey(), "QUEUED", null);
            return false;
        }
        if (!info.modelKey().equals(result.modelKey()) || !info.model().equals(result.model()))
            throw new IllegalArgumentException("An embedding from another model cannot be stored.");
        String vector =
                "["
                        + String.join(",", result.vector().stream().map(Object::toString).toList())
                        + "]";
        jdbc.sql(
                        """
                INSERT INTO garment_embedding(garment_id, wardrobe_id, model_key, dimensions, embedding, source_fingerprint, updated_at)
                VALUES (:garment, :wardrobe, :key, :dimensions, CAST(:vector AS vector), :fingerprint, :now)
                ON CONFLICT (garment_id, model_key) DO UPDATE SET embedding = EXCLUDED.embedding,
                    source_fingerprint = EXCLUDED.source_fingerprint, updated_at = EXCLUDED.updated_at
                """)
                .param("garment", material.garmentId())
                .param("wardrobe", material.wardrobeId())
                .param("key", info.modelKey())
                .param("dimensions", info.model().dimensions())
                .param("vector", vector)
                .param("fingerprint", material.fingerprint())
                .param("now", now())
                .update();
        state(event, info.modelKey(), "READY", null);
        return true;
    }

    @Transactional
    public void failed(OutboxEntry event, ModelInfo model, boolean terminal) {
        if (model != null)
            state(event, model.modelKey(), terminal ? "FAILED" : "QUEUED", "EMBEDDING_FAILURE");
    }

    private void state(OutboxEntry event, String key, String state, String failure) {
        jdbc.sql(
                        """
                UPDATE garment_embedding_work SET state = :state, lease_owner = NULL, lease_until = NULL,
                    failure_code = :failure, updated_at = :now
                WHERE garment_id = :garment AND model_key = :key AND lease_owner = :event
                """)
                .param("garment", event.aggregateId())
                .param("key", key)
                .param("event", event.id())
                .param("state", state)
                .param("failure", failure)
                .param("now", now())
                .update();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    public static final class Busy extends RuntimeException {}
}
