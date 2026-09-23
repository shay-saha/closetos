package com.closetos.search.application;

import com.closetos.garment.api.GarmentCategory;
import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.WardrobeGraph;
import com.closetos.wardrobe.api.WardrobeAccess;
import org.springframework.stereotype.Service;

@Service
public class WardrobeTopology {
    private final EmbeddingProviderPort provider;
    private final WardrobeAccess wardrobes;
    private final TopologySnapshot snapshot;

    public WardrobeTopology(
            EmbeddingProviderPort provider, WardrobeAccess wardrobes, TopologySnapshot snapshot) {
        this.provider = provider;
        this.wardrobes = wardrobes;
        this.snapshot = snapshot;
    }

    public WardrobeGraph graph(GarmentCategory category, int neighbours, int limit) {
        var wardrobe = wardrobes.currentWardrobeId();
        EmbeddingProviderPort.ModelInfo model;
        try {
            model = provider.model();
        } catch (DomainException exception) {
            if (exception.status() != 503) throw exception;
            model = null;
        }
        return snapshot.graph(wardrobe, category, neighbours, limit, model);
    }
}
