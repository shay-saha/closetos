package com.closetos.insights.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.PairingContext;
import com.closetos.insights.api.RecommendationInsights.DuplicatePair;
import com.closetos.insights.api.RecommendationInsights.Duplicates;
import com.closetos.insights.api.RecommendationInsights.EmbeddingCoverage;
import com.closetos.insights.api.RecommendationInsights.EmbeddingState;
import com.closetos.insights.api.RecommendationInsights.Pairing;
import com.closetos.insights.api.RecommendationInsights.WorksWith;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.outfit.api.OutfitConnections;
import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.GarmentAffinities;
import com.closetos.wardrobe.api.WardrobeAccess;
import com.closetos.wear.api.WearConnections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class RecommendationSnapshot {
    private static final int MAX_PHOTO_CANDIDATES = 2000;
    private final GarmentAccess garments;
    private final GarmentPresenter presenter;
    private final MediaAccess media;
    private final WardrobeAccess wardrobes;
    private final GarmentAffinities affinities;
    private final OutfitConnections outfits;
    private final WearConnections wear;
    private final DuplicateRanker duplicates;
    private final PairingRanker pairings;

    public RecommendationSnapshot(
            GarmentAccess garments,
            GarmentPresenter presenter,
            MediaAccess media,
            WardrobeAccess wardrobes,
            GarmentAffinities affinities,
            OutfitConnections outfits,
            WearConnections wear,
            DuplicateRanker duplicates,
            PairingRanker pairings) {
        this.garments = garments;
        this.presenter = presenter;
        this.media = media;
        this.wardrobes = wardrobes;
        this.affinities = affinities;
        this.outfits = outfits;
        this.wear = wear;
        this.duplicates = duplicates;
        this.pairings = pairings;
    }

    public Duplicates duplicates(GarmentCategory category, int limit, ModelInfo model) {
        var reviewed =
                reviewed().stream()
                        .filter(
                                piece ->
                                        category == null || piece.metadata().category() == category)
                        .toList();
        var photos =
                media.approvedPhotoGarments(
                        reviewed.stream().map(GarmentDetails::id).toList(),
                        wardrobes.currentWardrobeId());
        var selected =
                reviewed.stream()
                        .filter(piece -> photos.contains(piece.id()))
                        .sorted(Comparator.comparing(piece -> piece.id().toString()))
                        .limit(MAX_PHOTO_CANDIDATES)
                        .toList();
        var lookup =
                selected.stream()
                        .collect(Collectors.toMap(GarmentDetails::id, Function.identity()));
        var groups = new HashMap<String, List<UUID>>();
        for (var piece : selected) {
            groups.computeIfAbsent(
                            "category:" + piece.metadata().category(), ignored -> new ArrayList<>())
                    .add(piece.id());
            var layer = GarmentStructure.sharedLayer(piece);
            if (!layer.isEmpty())
                groups.computeIfAbsent("layer:" + layer, ignored -> new ArrayList<>())
                        .add(piece.id());
        }
        var indexed = new HashSet<UUID>();
        var ranked = new HashMap<String, DuplicateRanker.RankedPair>();
        if (model != null)
            for (var group : groups.values()) {
                var related = affinities.neighbours(group, model, 10);
                indexed.addAll(related.indexed());
                for (var link : related.links()) {
                    var first = lookup.get(link.source());
                    var second = lookup.get(link.target());
                    if (first == null || second == null) continue;
                    duplicates
                            .rank(first, second, link.affinity())
                            .ifPresent(
                                    pair ->
                                            ranked.put(
                                                    pair.pair().first().id()
                                                            + ":"
                                                            + pair.pair().second().id(),
                                                    pair));
                }
            }
        var shown =
                ranked.values().stream()
                        .sorted(
                                Comparator.comparingDouble(DuplicateRanker.RankedPair::score)
                                        .reversed()
                                        .thenComparing(pair -> pair.pair().first().id().toString())
                                        .thenComparing(
                                                pair -> pair.pair().second().id().toString()))
                        .limit(limit)
                        .map(DuplicateRanker.RankedPair::pair)
                        .toList();
        var signed =
                sign(
                        shown.stream()
                                .flatMap(
                                        pair ->
                                                java.util.stream.Stream.of(
                                                        pair.first(), pair.second()))
                                .toList());
        return new Duplicates(
                reviewed.size(),
                photos.size(),
                (int) selected.stream().filter(piece -> !ColourCompatibility.known(piece)).count(),
                ranked.size(),
                photos.size() > MAX_PHOTO_CANDIDATES,
                coverage(model, selected.size(), indexed.size()),
                shown.stream()
                        .map(
                                pair ->
                                        new DuplicatePair(
                                                signed.get(pair.first().id()),
                                                signed.get(pair.second().id()),
                                                pair.reasons()))
                        .toList(),
                List.of(
                        "Potential duplicates are suggestions to review; no pieces are automatically changed or labelled.",
                        "Only reviewed pieces with approved photos and current embeddings are compared.",
                        "Candidates use the same category, or a matching layer type across related categories, and compatible recorded colours.",
                        "Missing colour details cannot establish colour compatibility and are excluded from matches.",
                        "Up to ten embedding neighbours per piece and category are considered; this is an advisory shortlist, not an exhaustive duplicate audit.",
                        "Archived pieces are excluded. Larger photo wardrobes compare a stable selection of up to 2000 pieces."));
    }

    public WorksWith worksWith(UUID sourceId, PairingContext context, int limit, ModelInfo model) {
        var source = garments.owned(sourceId);
        if (source.processingStatus() != ProcessingStatus.READY
                || source.status() != GarmentStatus.AVAILABLE)
            throw new DomainException(
                    409,
                    "PAIRING_SOURCE_UNAVAILABLE",
                    "Choose a reviewed, available piece for pairing suggestions.");
        boolean matches = context.matches(source);
        var eligible =
                matches
                        ? reviewed().stream()
                                .filter(piece -> !piece.id().equals(sourceId))
                                .filter(
                                        piece ->
                                                piece.status() == GarmentStatus.AVAILABLE
                                                        && context.matches(piece)
                                                        && GarmentStructure.complementarySlot(
                                                                        source, piece)
                                                                > 0)
                                .toList()
                        : List.<GarmentDetails>of();
        var ids = eligible.stream().map(GarmentDetails::id).toList();
        var semantic =
                model == null
                        ? new GarmentAffinities.SourceAffinities(Set.of(), Map.of())
                        : affinities.from(sourceId, ids, model);
        var worn = wear.wornTogether(sourceId, ids);
        var saved = outfits.savedTogether(sourceId, ids);
        var shown =
                eligible.stream()
                        .flatMap(
                                candidate ->
                                        pairings
                                                .rank(
                                                        source,
                                                        candidate,
                                                        context,
                                                        semantic.affinities().get(candidate.id()),
                                                        worn.getOrDefault(candidate.id(), 0),
                                                        saved.getOrDefault(candidate.id(), 0))
                                                .stream())
                        .sorted(
                                Comparator.comparingDouble(PairingRanker.RankedPairing::score)
                                        .reversed()
                                        .thenComparing(
                                                item -> item.pairing().garment().metadata().name(),
                                                String.CASE_INSENSITIVE_ORDER)
                                        .thenComparing(
                                                item -> item.pairing().garment().id().toString()))
                        .limit(limit)
                        .map(PairingRanker.RankedPairing::pairing)
                        .toList();
        var toSign = new ArrayList<>(shown.stream().map(Pairing::garment).toList());
        toSign.add(source);
        var signed = sign(toSign);
        return new WorksWith(
                signed.get(sourceId),
                context,
                matches,
                eligible.size(),
                coverage(model, eligible.size() + 1, semantic.indexed().size()),
                shown.stream()
                        .map(
                                item ->
                                        new Pairing(
                                                signed.get(item.garment().id()),
                                                item.wornTogetherCount(),
                                                item.savedTogetherCount(),
                                                item.semanticAffinityKnown(),
                                                item.reasons()))
                        .toList(),
                List.of(
                        "Only reviewed, available pieces with complementary garment slots are suggested.",
                        "Requested season, formality and weather filters apply to both the selected piece and the suggestions; missing required tags do not count as matches.",
                        "Weather assumptions match explicit tags such as cold-weather, mild-weather, hot-weather or rain; no live weather or waterproof performance is inferred.",
                        "Ranking combines garment slots, recorded style and colour, current embedding affinity, recorded wears together and saved outfits.",
                        "Removed wear entries and archived saved outfits do not contribute to ranking.",
                        "Unavailable or pending embeddings leave honest metadata and history suggestions; these pairings are a starting point, not a guarantee of an outfit."));
    }

    private List<GarmentDetails> reviewed() {
        return garments.ownedDetails(garments.ownedIds()).stream()
                .filter(
                        piece ->
                                piece.processingStatus() == ProcessingStatus.READY
                                        && piece.status() != GarmentStatus.ARCHIVED)
                .toList();
    }

    private EmbeddingCoverage coverage(ModelInfo model, int eligible, int indexed) {
        var state =
                eligible == 0
                        ? EmbeddingState.NOT_NEEDED
                        : model == null
                                ? EmbeddingState.UNAVAILABLE
                                : indexed < eligible
                                        ? EmbeddingState.PENDING
                                        : EmbeddingState.READY;
        return new EmbeddingCoverage(
                state, model == null ? null : model.model(), eligible, indexed);
    }

    private Map<UUID, GarmentDetails> sign(List<GarmentDetails> pieces) {
        var unique =
                pieces.stream()
                        .collect(
                                Collectors.toMap(
                                        GarmentDetails::id,
                                        Function.identity(),
                                        (first, ignored) -> first));
        if (unique.isEmpty()) return Map.of();
        return presenter.page(new GarmentPage(List.copyOf(unique.values()), null)).items().stream()
                .collect(Collectors.toMap(GarmentDetails::id, Function.identity()));
    }
}
