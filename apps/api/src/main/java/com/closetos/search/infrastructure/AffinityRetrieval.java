package com.closetos.search.infrastructure;

import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.GarmentAffinities.SourceAffinities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AffinityRetrieval {
    private final JdbcClient jdbc;

    public AffinityRetrieval(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SourceAffinities from(
            UUID wardrobe, UUID source, List<UUID> candidates, ModelInfo model) {
        var selected = new ArrayList<>(candidates);
        selected.add(source);
        String current =
                "SELECT * FROM ("
                        + VectorRetrieval.CURRENT_VECTOR
                        + ") current WHERE garment_id IN (:candidates)";
        var indexed =
                jdbc.sql("SELECT garment_id FROM (" + current + ") selected")
                        .param("wardrobe", wardrobe)
                        .param("model", model.modelKey())
                        .param("candidates", selected)
                        .query(UUID.class)
                        .list();
        if (!indexed.contains(source) || candidates.isEmpty())
            return new SourceAffinities(Set.copyOf(indexed), Map.of());
        var pairs =
                jdbc.sql(
                                "WITH current_vectors AS MATERIALIZED ("
                                        + current
                                        + ") "
                                        + """
                SELECT t.garment_id AS id, greatest(0::double precision, least(1::double precision,
                    1 - (s.embedding <=> t.embedding))) AS affinity
                FROM current_vectors s CROSS JOIN current_vectors t
                WHERE s.garment_id = :source AND t.garment_id <> :source ORDER BY t.garment_id
                """)
                        .param("wardrobe", wardrobe)
                        .param("model", model.modelKey())
                        .param("candidates", selected)
                        .param("source", source)
                        .query(Pair.class)
                        .list();
        return new SourceAffinities(
                Set.copyOf(indexed),
                pairs.stream().collect(Collectors.toUnmodifiableMap(Pair::id, Pair::affinity)));
    }

    private record Pair(UUID id, double affinity) {}
}
