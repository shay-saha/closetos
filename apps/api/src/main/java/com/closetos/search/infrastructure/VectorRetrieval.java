package com.closetos.search.infrastructure;

import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.SearchMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class VectorRetrieval {
    private static final String CURRENT_VECTOR =
            """
            SELECT e.garment_id, e.embedding
            FROM garment_embedding e
            JOIN garment_embedding_work w ON w.garment_id = e.garment_id AND w.model_key = e.model_key
            WHERE e.wardrobe_id = :wardrobe AND e.model_key = :model AND w.state = 'READY'
                AND w.source_fingerprint = e.source_fingerprint
                AND NOT EXISTS (SELECT 1 FROM outbox_event o WHERE o.aggregate_id = e.garment_id
                    AND o.event_type = 'GENERATE_EMBEDDING'
                    AND (o.published_at IS NULL OR (o.failure_detail IS NOT NULL AND o.created_at > e.updated_at)))
            """;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public VectorRetrieval(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public java.util.Optional<List<Double>> source(UUID wardrobe, UUID garment, ModelInfo model) {
        return jdbc.sql(
                        "SELECT embedding::text FROM ("
                                + CURRENT_VECTOR
                                + ") current WHERE garment_id = :garment")
                .param("wardrobe", wardrobe)
                .param("model", model.modelKey())
                .param("garment", garment)
                .query(String.class)
                .optional()
                .map(value -> json.readValue(value, new TypeReference<List<Double>>() {}));
    }

    public int indexed(UUID wardrobe, List<UUID> candidates, ModelInfo model) {
        if (candidates.isEmpty()) return 0;
        return jdbc.sql(
                        "SELECT count(*) FROM ("
                                + CURRENT_VECTOR
                                + ") current WHERE garment_id IN (:candidates)")
                .param("wardrobe", wardrobe)
                .param("model", model.modelKey())
                .param("candidates", candidates)
                .query(Integer.class)
                .single();
    }

    public List<Ranked> rank(
            UUID wardrobe,
            List<UUID> candidates,
            SearchMode mode,
            String text,
            ModelInfo model,
            List<Double> vector,
            SearchCursor.Position after,
            int limit) {
        if (candidates.isEmpty()) return List.of();
        boolean semantic = model != null;
        String lexical =
                text == null
                        ? "0::real"
                        : "ts_rank_cd(g.search_document, websearch_to_tsquery('english', :text))";
        String distance =
                semantic ? "(e.embedding <=> CAST(:vector AS vector))" : "NULL::double precision";
        String join = semantic ? "LEFT JOIN current_vectors e ON e.garment_id = g.id" : "";
        String include =
                switch (mode) {
                    case FILTERS -> "TRUE";
                    case KEYWORD -> "lexical > 0";
                    case SEMANTIC -> "distance IS NOT NULL";
                    case HYBRID -> "lexical > 0 OR distance IS NOT NULL";
                };
        String score =
                switch (mode) {
                    case FILTERS -> "extract(epoch FROM created_at)::double precision";
                    case KEYWORD -> "lexical::double precision";
                    case SEMANTIC -> "(1 - distance)::double precision";
                    case HYBRID ->
                            "((CASE WHEN lexical > 0 THEN 1.0 / (60 + lexical_rank) ELSE 0 END) + "
                                    + "(CASE WHEN distance IS NOT NULL THEN 1.0 / (60 + semantic_rank) ELSE 0 END))::double precision";
                };
        String sql =
                "WITH "
                        + (semantic
                                ? "current_vectors AS MATERIALIZED (" + CURRENT_VECTOR + "), "
                                : "")
                        + """
                candidates AS MATERIALIZED (
                    SELECT g.id, g.created_at, %s AS lexical, %s AS distance
                    FROM garment g %s WHERE g.wardrobe_id = :wardrobe AND g.id IN (:candidates)
                ), ranked AS (
                    SELECT *, row_number() OVER (ORDER BY lexical DESC, id) AS lexical_rank,
                        row_number() OVER (ORDER BY distance ASC NULLS LAST, id) AS semantic_rank
                    FROM candidates WHERE %s
                ), scored AS (SELECT *, %s AS score FROM ranked)
                SELECT id, score, lexical > 0 AS keyword_match, distance IS NOT NULL AS semantic_match
                FROM scored
                """
                                .formatted(lexical, distance, join, include, score)
                        + (after == null
                                ? ""
                                : "WHERE score < :afterScore OR (score = :afterScore AND id > :afterId) ")
                        + "ORDER BY score DESC, id ASC LIMIT :limit";
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("wardrobe", wardrobe);
        parameters.put("candidates", candidates);
        parameters.put("limit", limit);
        if (text != null) parameters.put("text", text);
        if (semantic) {
            parameters.put("model", model.modelKey());
            parameters.put(
                    "vector",
                    "[" + String.join(",", vector.stream().map(Object::toString).toList()) + "]");
        }
        if (after != null) {
            parameters.put("afterScore", after.score());
            parameters.put("afterId", after.id());
        }
        return jdbc.sql(sql)
                .params(parameters)
                .query(
                        (rs, row) ->
                                new Ranked(
                                        rs.getObject("id", UUID.class),
                                        rs.getDouble("score"),
                                        rs.getBoolean("keyword_match"),
                                        rs.getBoolean("semantic_match")))
                .list();
    }

    public record Ranked(UUID id, double score, boolean keywordMatch, boolean semanticMatch) {}
}
