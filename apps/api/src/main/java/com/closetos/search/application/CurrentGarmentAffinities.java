package com.closetos.search.application;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.GarmentAffinities;
import com.closetos.search.infrastructure.AffinityRetrieval;
import com.closetos.search.infrastructure.TopologyRetrieval;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentGarmentAffinities implements GarmentAffinities {
    private final EmbeddingProviderPort provider;
    private final TopologyRetrieval neighbours;
    private final AffinityRetrieval retrieval;
    private final WardrobeAccess wardrobes;

    public CurrentGarmentAffinities(
            EmbeddingProviderPort provider,
            TopologyRetrieval neighbours,
            AffinityRetrieval retrieval,
            WardrobeAccess wardrobes) {
        this.provider = provider;
        this.neighbours = neighbours;
        this.retrieval = retrieval;
        this.wardrobes = wardrobes;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public Optional<ModelInfo> currentModel() {
        try {
            return Optional.of(provider.model());
        } catch (DomainException exception) {
            if (exception.status() != 503) throw exception;
            return Optional.empty();
        }
    }

    @Override
    public Neighbours neighbours(List<UUID> candidates, ModelInfo model, int perPiece) {
        var result =
                neighbours.neighbours(wardrobes.currentWardrobeId(), candidates, model, perPiece);
        return new Neighbours(
                result.indexed(),
                result.edges().stream()
                        .map(edge -> new Link(edge.source(), edge.target(), edge.weight()))
                        .toList());
    }

    @Override
    public SourceAffinities from(UUID source, List<UUID> candidates, ModelInfo model) {
        return retrieval.from(wardrobes.currentWardrobeId(), source, candidates, model);
    }
}
