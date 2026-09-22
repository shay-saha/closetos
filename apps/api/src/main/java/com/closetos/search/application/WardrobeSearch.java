package com.closetos.search.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.SearchMode;
import com.closetos.search.api.SearchPage;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WardrobeSearch {
    private final GarmentAccess garments;
    private final WardrobeAccess wardrobes;
    private final SearchConstraints parser;
    private final EmbeddingProviderPort provider;
    private final QueryEmbeddings queries;
    private final SearchResults results;
    private final JsonMapper json;

    public WardrobeSearch(
            GarmentAccess garments,
            WardrobeAccess wardrobes,
            SearchConstraints parser,
            EmbeddingProviderPort provider,
            QueryEmbeddings queries,
            SearchResults results,
            JsonMapper json) {
        this.garments = garments;
        this.wardrobes = wardrobes;
        this.parser = parser;
        this.provider = provider;
        this.queries = queries;
        this.results = results;
        this.json = json;
    }

    public SearchPage find(GarmentFilter filter, SearchMode requested, UUID similar, String image) {
        if (filter == null) filter = json.readValue("{}", GarmentFilter.class);
        if (image != null) {
            try {
                if (image.length() > 11_184_812
                        || Base64.getDecoder().decode(image).length > 8 * 1024 * 1024)
                    throw DomainException.invalid("Use a photograph smaller than 8 MB.");
            } catch (IllegalArgumentException exception) {
                throw DomainException.invalid("Invalid photograph encoding.");
            }
        }
        boolean hasInput = filter.q() != null || similar != null || image != null;
        SearchMode mode =
                requested == null ? (hasInput ? SearchMode.HYBRID : SearchMode.FILTERS) : requested;
        if (mode == SearchMode.FILTERS && hasInput
                || mode != SearchMode.FILTERS && !hasInput
                || mode == SearchMode.KEYWORD && (image != null || similar != null))
            throw DomainException.invalid("Choose a search mode that supports this query.");
        UUID wardrobe = wardrobes.currentWardrobeId();
        if (similar != null) garments.owned(similar);
        var parsed = parser.parse(mode == SearchMode.HYBRID ? filter.q() : null);
        EmbeddingProviderPort.ModelInfo model = null;
        List<Double> vector = null;
        if (mode == SearchMode.SEMANTIC || mode == SearchMode.HYBRID) {
            model = provider.model();
            if (filter.q() != null || image != null) {
                var query =
                        queries.embed(
                                wardrobe,
                                new EmbeddingProviderPort.Input(filter.q(), null, null, image),
                                model);
                if (!model.modelKey().equals(query.modelKey())
                        || !model.model().equals(query.model()))
                    throw new IllegalStateException(
                            "Search query belongs to another embedding model.");
                vector = query.vector();
            }
        }
        return results.find(wardrobe, filter, mode, similar, image != null, model, vector, parsed);
    }
}
