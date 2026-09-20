package com.closetos.intelligence.application;

import com.closetos.intelligence.api.AnalysisDocument;
import com.closetos.intelligence.api.SuggestionAccess;
import com.closetos.intelligence.api.SuggestionDetails;
import com.closetos.intelligence.api.SuggestionStatus;
import com.closetos.platform.api.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class SuggestionRegistry implements SuggestionAccess {
    private static final String COLUMNS =
            "id, garment_id, image_id, model_id, model_version, prompt_version, pipeline_version, suggestion_json::text, status, version, created_at, accepted_at, rejected_at";
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SuggestionSchema schema;

    SuggestionRegistry(JdbcClient jdbc, JsonMapper json, SuggestionSchema schema) {
        this.jdbc = jdbc;
        this.json = json;
        this.schema = schema;
    }

    @Override
    public AnalysisDocument validate(UUID imageId, String pipelineVersion, String document) {
        return schema.validate(imageId, pipelineVersion, document);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID jobId, UUID garmentId, UUID wardrobeId, AnalysisDocument document) {
        jdbc.sql(
                        "UPDATE garment_ai_suggestion SET status = 'SUPERSEDED', version = version + 1 WHERE image_id = :image AND status = 'PENDING' AND processing_job_id <> :job")
                .param("image", document.imageId())
                .param("job", jobId)
                .update();
        jdbc.sql(
                        """
                INSERT INTO garment_ai_suggestion (id, garment_id, image_id, processing_job_id, wardrobe_id,
                    model_id, model_version, prompt_version, pipeline_version, suggestion_json)
                VALUES (:id, :garment, :image, :job, :wardrobe, :model, :modelVersion, :prompt, :pipeline, CAST(:document AS jsonb))
                ON CONFLICT (processing_job_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("garment", garmentId)
                .param("image", document.imageId())
                .param("job", jobId)
                .param("wardrobe", wardrobeId)
                .param("model", document.modelId())
                .param("modelVersion", document.modelVersion())
                .param("prompt", document.promptVersion())
                .param("pipeline", document.pipelineVersion())
                .param("document", json.writeValueAsString(document))
                .update();
    }

    @Override
    public List<SuggestionDetails> list(UUID garmentId, UUID wardrobeId) {
        return jdbc.sql(
                        "SELECT "
                                + COLUMNS
                                + " FROM garment_ai_suggestion WHERE garment_id = :garment AND wardrobe_id = :wardrobe ORDER BY created_at DESC, id")
                .param("garment", garmentId)
                .param("wardrobe", wardrobeId)
                .query(this::details)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SuggestionDetails pending(UUID id, UUID garment, UUID wardrobe, long version) {
        var suggestion =
                jdbc.sql(
                                "SELECT "
                                        + COLUMNS
                                        + " FROM garment_ai_suggestion WHERE id = :id AND garment_id = :garment AND wardrobe_id = :wardrobe FOR UPDATE")
                        .param("id", id)
                        .param("garment", garment)
                        .param("wardrobe", wardrobe)
                        .query(this::details)
                        .optional()
                        .orElseThrow(() -> DomainException.notFound("Suggestion"));
        if (suggestion.status() != SuggestionStatus.PENDING || suggestion.version() != version)
            throw DomainException.conflict();
        return suggestion;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accepted(UUID id, UUID wardrobe, ObjectNode metadata) {
        int changed =
                jdbc.sql(
                                "UPDATE garment_ai_suggestion SET status = 'ACCEPTED', accepted_at = now(), decision_json = CAST(:decision AS jsonb), version = version + 1 WHERE id = :id AND wardrobe_id = :wardrobe AND status = 'PENDING'")
                        .param("id", id)
                        .param("wardrobe", wardrobe)
                        .param("decision", json.writeValueAsString(metadata))
                        .update();
        if (changed != 1) throw DomainException.conflict();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(UUID id, UUID wardrobe) {
        int changed =
                jdbc.sql(
                                "UPDATE garment_ai_suggestion SET status = 'REJECTED', rejected_at = now(), version = version + 1 WHERE id = :id AND wardrobe_id = :wardrobe AND status = 'PENDING'")
                        .param("id", id)
                        .param("wardrobe", wardrobe)
                        .update();
        if (changed != 1) throw DomainException.conflict();
    }

    @Override
    public ObjectNode canonicalValues(SuggestionDetails suggestion) {
        return schema.canonicalValues(suggestion.suggestions());
    }

    private SuggestionDetails details(ResultSet row, int index) throws SQLException {
        AnalysisDocument document =
                json.readValue(row.getString("suggestion_json"), AnalysisDocument.class);
        var accepted = row.getTimestamp("accepted_at");
        var rejected = row.getTimestamp("rejected_at");
        return new SuggestionDetails(
                row.getObject("id", UUID.class),
                row.getObject("garment_id", UUID.class),
                row.getObject("image_id", UUID.class),
                row.getString("model_id"),
                row.getString("model_version"),
                row.getString("prompt_version"),
                row.getString("pipeline_version"),
                document.suggestions(),
                SuggestionStatus.valueOf(row.getString("status")),
                row.getLong("version"),
                row.getTimestamp("created_at").toInstant(),
                accepted == null ? null : accepted.toInstant(),
                rejected == null ? null : rejected.toInstant());
    }
}
