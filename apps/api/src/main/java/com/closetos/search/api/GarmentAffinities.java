package com.closetos.search.api;

import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface GarmentAffinities {
    Optional<ModelInfo> currentModel();

    Neighbours neighbours(List<UUID> candidates, ModelInfo model, int perPiece);

    SourceAffinities from(UUID source, List<UUID> candidates, ModelInfo model);

    record Link(UUID source, UUID target, double affinity) {}

    record Neighbours(Set<UUID> indexed, List<Link> links) {}

    record SourceAffinities(Set<UUID> indexed, Map<UUID, Double> affinities) {}
}
