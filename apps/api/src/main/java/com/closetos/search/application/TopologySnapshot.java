package com.closetos.search.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.WardrobeGraph;
import com.closetos.search.api.WardrobeGraph.EmbeddingAvailability;
import com.closetos.search.infrastructure.TopologyRetrieval;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TopologySnapshot {
    private final GarmentAccess garments;
    private final GarmentPresenter presenter;
    private final TopologyRetrieval retrieval;
    private final JsonMapper json;

    public TopologySnapshot(
            GarmentAccess garments,
            GarmentPresenter presenter,
            TopologyRetrieval retrieval,
            JsonMapper json) {
        this.garments = garments;
        this.presenter = presenter;
        this.retrieval = retrieval;
        this.json = json;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public WardrobeGraph graph(
            UUID wardrobe, GarmentCategory category, int neighbours, int limit, ModelInfo model) {
        var constraints = json.createObjectNode().put("processingStatus", "READY");
        if (category != null) constraints.put("category", category.name());
        var filter = json.treeToValue(constraints, GarmentFilter.class);
        var eligible = garments.matchingIds(filter, null);
        var selected =
                eligible.stream()
                        .sorted(Comparator.comparing(UUID::toString))
                        .limit(limit)
                        .toList();
        var relationships =
                model == null
                        ? new TopologyRetrieval.Relationships(Set.of(), List.of())
                        : retrieval.neighbours(wardrobe, selected, model, neighbours);
        var details =
                presenter.page(new GarmentPage(garments.ownedDetails(selected), null)).items();
        var nodes =
                details.stream()
                        .sorted(Comparator.comparing(garment -> garment.id().toString()))
                        .map(
                                garment ->
                                        node(
                                                garment,
                                                relationships.indexed().contains(garment.id())))
                        .toList();
        return new WardrobeGraph(
                nodes,
                relationships.edges(),
                eligible.size(),
                relationships.indexed().size(),
                eligible.size() > limit,
                model == null ? null : model.model(),
                model == null
                        ? EmbeddingAvailability.UNAVAILABLE
                        : EmbeddingAvailability.AVAILABLE);
    }

    private WardrobeGraph.Node node(GarmentDetails garment, boolean indexed) {
        return new WardrobeGraph.Node(
                garment.id(),
                garment.metadata().name(),
                garment.metadata().category(),
                garment.assets(),
                garment.metadata().primaryColourHex(),
                garment.wearCount(),
                garment.lastWornAt(),
                garment.costPerWear(),
                garment.metadata().purchaseCurrency(),
                indexed);
    }
}
