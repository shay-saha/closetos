package com.closetos.search.infrastructure;

import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.WardrobeGraph;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TopologyRetrieval {
    private final JdbcClient jdbc;

    public TopologyRetrieval(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Relationships neighbours(
            UUID wardrobe, List<UUID> candidates, ModelInfo model, int count) {
        if (candidates.isEmpty()) return new Relationships(Set.of(), List.of());
        String current =
                "SELECT * FROM ("
                        + VectorRetrieval.CURRENT_VECTOR
                        + ") current WHERE garment_id IN (:candidates)";
        var indexed =
                jdbc.sql("SELECT garment_id FROM (" + current + ") selected")
                        .param("wardrobe", wardrobe)
                        .param("model", model.modelKey())
                        .param("candidates", candidates)
                        .query(UUID.class)
                        .list();
        if (indexed.size() < 2) return new Relationships(Set.copyOf(indexed), List.of());
        var edges =
                jdbc.sql(
                                "WITH current_vectors AS MATERIALIZED ("
                                        + current
                                        + "), "
                                        + """
                directed AS (
                    SELECT least(s.garment_id, n.garment_id) AS source,
                        greatest(s.garment_id, n.garment_id) AS target, n.weight
                    FROM current_vectors s CROSS JOIN LATERAL (
                        SELECT t.garment_id,
                            greatest(0::double precision, least(1::double precision,
                                1 - (s.embedding <=> t.embedding))) AS weight
                        FROM current_vectors t WHERE t.garment_id <> s.garment_id
                        ORDER BY s.embedding <=> t.embedding, t.garment_id LIMIT :neighbours
                    ) n
                )
                SELECT source, target, max(weight) AS weight FROM directed
                GROUP BY source, target ORDER BY source, target
                """)
                        .param("wardrobe", wardrobe)
                        .param("model", model.modelKey())
                        .param("candidates", candidates)
                        .param("neighbours", count)
                        .query(WardrobeGraph.Edge.class)
                        .list();
        return new Relationships(Set.copyOf(indexed), edges);
    }

    public record Relationships(Set<UUID> indexed, List<WardrobeGraph.Edge> edges) {}
}
