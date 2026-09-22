package com.closetos.search.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentFilter;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.SearchMode;
import com.closetos.search.api.SearchPage;
import com.closetos.search.infrastructure.SearchCursor;
import com.closetos.search.infrastructure.VectorRetrieval;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class SearchResults {
    private final GarmentAccess garments;
    private final GarmentPresenter presenter;
    private final VectorRetrieval retrieval;
    private final SearchCursor cursors;
    private final JsonMapper json;
    private final Clock clock;

    public SearchResults(
            GarmentAccess garments,
            GarmentPresenter presenter,
            VectorRetrieval retrieval,
            SearchCursor cursors,
            JsonMapper json,
            Clock clock) {
        this.garments = garments;
        this.presenter = presenter;
        this.retrieval = retrieval;
        this.cursors = cursors;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SearchPage find(
            UUID wardrobe,
            GarmentFilter filter,
            SearchMode mode,
            UUID similar,
            boolean imageQuery,
            EmbeddingProviderPort.ModelInfo model,
            List<Double> queryVector,
            SearchConstraints.Parsed parsed) {
        ObjectNode hard = json.valueToTree(filter);
        hard.remove("q");
        hard.remove("cursor");
        var hardFilter = json.treeToValue(hard, GarmentFilter.class);
        var candidates = new ArrayList<>(garments.matchingIds(hardFilter, parsed.query()));
        if (similar != null) candidates.remove(similar);
        if (mode == SearchMode.FILTERS) {
            var page = presenter.page(garments.list(filter));
            return new SearchPage(
                    page.items().stream()
                            .map(
                                    garment ->
                                            new SearchPage.Hit(
                                                    garment,
                                                    List.of("Matches your selected filters")))
                            .toList(),
                    page.nextCursor(),
                    mode,
                    List.of(),
                    candidates.size(),
                    0);
        }
        List<Double> vector = queryVector;
        if (similar != null) {
            garments.owned(similar);
            var source =
                    retrieval
                            .source(wardrobe, similar, model)
                            .orElseThrow(
                                    () ->
                                            new DomainException(
                                                    409,
                                                    "SEARCH_SOURCE_PENDING",
                                                    "This piece is still being prepared for similarity search."));
            if (vector == null) vector = source;
            else {
                var combined = new ArrayList<Double>();
                for (int index = 0; index < vector.size(); index++)
                    combined.add(source.get(index) + vector.get(index));
                vector =
                        new EmbeddingProviderPort.VectorResult(
                                        model.modelKey(), model.model(), combined)
                                .vector();
            }
        }
        var scope = new LinkedHashMap<String, Object>();
        scope.put("filters", hardFilter);
        scope.put("wardrobe", wardrobe);
        scope.put("mode", mode);
        scope.put("text", filter.q());
        scope.put("similar", similar);
        scope.put("model", model);
        scope.put("vector", vector);
        scope.put("date", LocalDate.now(clock));
        scope.put("rules", parsed.query());
        ObjectNode scopeJson = json.valueToTree(scope);
        ((ObjectNode) scopeJson.get("filters")).remove("limit");
        String fingerprint = QueryEmbeddings.hash(scopeJson.toString());
        var after = filter.cursor() == null ? null : cursors.decode(filter.cursor(), fingerprint);
        var ranked =
                retrieval.rank(
                        wardrobe,
                        candidates,
                        mode,
                        filter.q(),
                        model,
                        vector,
                        after,
                        filter.limit() + 1);
        var page = ranked.stream().limit(filter.limit()).toList();
        var details = garments.ownedDetails(page.stream().map(VectorRetrieval.Ranked::id).toList());
        var signed =
                presenter.page(new GarmentPage(details, null)).items().stream()
                        .collect(Collectors.toMap(GarmentDetails::id, Function.identity()));
        var results =
                page.stream()
                        .filter(item -> signed.containsKey(item.id()))
                        .map(
                                item -> {
                                    var reasons = new ArrayList<String>();
                                    if (item.keywordMatch())
                                        reasons.add("Matches words in the garment details");
                                    if (item.semanticMatch())
                                        reasons.add(
                                                similar != null
                                                        ? "Related to the selected piece"
                                                        : imageQuery
                                                                ? "Related to your photograph"
                                                                : "Related to your description");
                                    reasons.addAll(
                                            parsed.constraints().stream()
                                                    .map(SearchPage.Constraint::explanation)
                                                    .toList());
                                    if (reasons.isEmpty())
                                        reasons.add("Matches your selected filters");
                                    return new SearchPage.Hit(signed.get(item.id()), reasons);
                                })
                        .toList();
        String next =
                ranked.size() <= filter.limit() || page.isEmpty()
                        ? null
                        : cursors.encode(page.getLast().id(), page.getLast().score(), fingerprint);
        int indexed = model == null ? 0 : retrieval.indexed(wardrobe, candidates, model);
        return new SearchPage(
                results, next, mode, parsed.constraints(), candidates.size(), indexed);
    }
}
