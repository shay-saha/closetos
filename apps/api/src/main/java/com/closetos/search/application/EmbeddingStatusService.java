package com.closetos.search.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.search.api.EmbeddingStatus;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingStatusService {
    private final GarmentAccess garments;
    private final WardrobeAccess wardrobes;
    private final EmbeddingInputLoader inputs;
    private final JdbcClient jdbc;

    public EmbeddingStatusService(
            GarmentAccess garments,
            WardrobeAccess wardrobes,
            EmbeddingInputLoader inputs,
            JdbcClient jdbc) {
        this.garments = garments;
        this.wardrobes = wardrobes;
        this.inputs = inputs;
        this.jdbc = jdbc;
    }

    @Transactional
    public EmbeddingStatus get(UUID garment) {
        var piece = garments.owned(garment);
        if (piece.processingStatus() != ProcessingStatus.READY)
            return new EmbeddingStatus(garment, "WAITING_FOR_REVIEW", null, piece.updatedAt());
        UUID wardrobe = wardrobes.currentWardrobeId();
        var fingerprint = inputs.load(garment, wardrobe).orElseThrow().fingerprint();
        var result =
                jdbc.sql(
                                "SELECT state, source_fingerprint, failure_code, updated_at FROM garment_embedding_work WHERE garment_id = :garment AND wardrobe_id = :wardrobe ORDER BY updated_at DESC LIMIT 1")
                        .param("garment", garment)
                        .param("wardrobe", wardrobe)
                        .query(
                                (rs, row) ->
                                        new EmbeddingStatus(
                                                garment,
                                                fingerprint.equals(
                                                                rs.getString("source_fingerprint"))
                                                        ? rs.getString("state")
                                                        : "QUEUED",
                                                fingerprint.equals(
                                                                rs.getString("source_fingerprint"))
                                                        ? rs.getString("failure_code")
                                                        : null,
                                                rs.getTimestamp("updated_at").toInstant()))
                        .optional();
        if (result.isPresent()) return result.get();
        return jdbc.sql(
                        "SELECT published_at, failure_detail, created_at FROM outbox_event WHERE aggregate_id = :garment AND event_type = 'GENERATE_EMBEDDING' ORDER BY created_at DESC LIMIT 1")
                .param("garment", garment)
                .query(
                        (rs, row) ->
                                new EmbeddingStatus(
                                        garment,
                                        rs.getTimestamp("published_at") != null
                                                        && rs.getString("failure_detail") != null
                                                ? "FAILED"
                                                : "QUEUED",
                                        rs.getString("failure_detail") == null
                                                ? null
                                                : "EMBEDDING_FAILURE",
                                        rs.getTimestamp("created_at").toInstant()))
                .optional()
                .orElseGet(
                        () ->
                                new EmbeddingStatus(
                                        garment, "NOT_REQUESTED", null, piece.updatedAt()));
    }
}
