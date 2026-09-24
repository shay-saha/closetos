package com.closetos.insights.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.identity.api.ProfileAccess;
import com.closetos.insights.api.HistoryInsights;
import com.closetos.insights.api.HistoryInsights.CurrencyCosts;
import com.closetos.insights.api.HistoryInsights.ForgottenPiece;
import com.closetos.insights.api.InsightSeason;
import com.closetos.media.api.ProcessingStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class WardrobeHistory {
    private static final Comparator<GarmentDetails> NAME_ORDER =
            Comparator.comparing(
                            (GarmentDetails garment) -> garment.metadata().name(),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(garment -> garment.id().toString());
    private final GarmentAccess garments;
    private final GarmentPresenter presenter;
    private final ProfileAccess profiles;
    private final ForgottenRanker ranker;
    private final Clock clock;

    public WardrobeHistory(
            GarmentAccess garments,
            GarmentPresenter presenter,
            ProfileAccess profiles,
            ForgottenRanker ranker,
            Clock clock) {
        this.garments = garments;
        this.presenter = presenter;
        this.profiles = profiles;
        this.ranker = ranker;
        this.clock = clock;
    }

    public HistoryInsights.Usage usage(boolean includeArchived, int limit) {
        var pieces = reviewed(includeArchived);
        var most =
                pieces.stream()
                        .filter(garment -> garment.wearCount() > 0)
                        .sorted(
                                Comparator.comparingInt(GarmentDetails::wearCount)
                                        .reversed()
                                        .thenComparing(NAME_ORDER))
                        .limit(limit)
                        .toList();
        var least =
                pieces.stream()
                        .sorted(
                                Comparator.comparingInt(GarmentDetails::wearCount)
                                        .thenComparing(NAME_ORDER))
                        .limit(limit)
                        .toList();
        var never =
                pieces.stream()
                        .filter(garment -> garment.wearCount() == 0)
                        .sorted(NAME_ORDER)
                        .limit(limit)
                        .toList();
        var signed = sign(List.of(most, least, never));
        var categories = new EnumMap<GarmentCategory, Integer>(GarmentCategory.class);
        var availability = new EnumMap<GarmentStatus, Integer>(GarmentStatus.class);
        for (var piece : pieces) {
            categories.merge(piece.metadata().category(), 1, Integer::sum);
            availability.merge(piece.status(), 1, Integer::sum);
        }
        return new HistoryInsights.Usage(
                today(),
                includeArchived,
                pieces.size(),
                pieces.stream().mapToLong(GarmentDetails::wearCount).sum(),
                (int) pieces.stream().filter(garment -> garment.wearCount() == 0).count(),
                unknownLastWorn(pieces),
                Map.copyOf(categories),
                Map.copyOf(availability),
                signed(most, signed),
                signed(least, signed),
                signed(never, signed),
                List.of(
                        "Counts use recorded wear history for reviewed pieces.",
                        "Garment wear occurrences count each piece worn, rather than days or outfits.",
                        "Never worn means no wear has been recorded; missing last-worn dates remain unknown.",
                        includeArchived
                                ? "Archived pieces are included."
                                : "Archived pieces are excluded."));
    }

    public HistoryInsights.CostPerWear costPerWear(boolean includeArchived, int limit) {
        var pieces = reviewed(includeArchived);
        var byCurrency =
                pieces.stream()
                        .filter(
                                garment ->
                                        garment.metadata().purchasePrice() != null
                                                && garment.metadata().purchaseCurrency() != null)
                        .collect(
                                Collectors.groupingBy(
                                        garment -> garment.metadata().purchaseCurrency(),
                                        TreeMap::new,
                                        Collectors.toList()));
        var costs =
                byCurrency.entrySet().stream()
                        .map(
                                entry -> {
                                    var worn =
                                            entry.getValue().stream()
                                                    .filter(garment -> garment.wearCount() > 0)
                                                    .toList();
                                    var order =
                                            Comparator.comparing(GarmentDetails::costPerWear)
                                                    .thenComparing(NAME_ORDER);
                                    return new CurrencyCosts(
                                            entry.getKey(),
                                            entry.getValue().size(),
                                            entry.getValue().stream()
                                                    .map(
                                                            garment ->
                                                                    garment.metadata()
                                                                            .purchasePrice())
                                                    .reduce(BigDecimal.ZERO, BigDecimal::add),
                                            worn.stream().sorted(order).limit(limit).toList(),
                                            worn.stream()
                                                    .sorted(
                                                            Comparator.comparing(
                                                                            GarmentDetails
                                                                                    ::costPerWear)
                                                                    .reversed()
                                                                    .thenComparing(NAME_ORDER))
                                                    .limit(limit)
                                                    .toList(),
                                            entry.getValue().stream()
                                                    .filter(garment -> garment.wearCount() == 0)
                                                    .sorted(NAME_ORDER)
                                                    .limit(limit)
                                                    .toList());
                                })
                        .toList();
        var signed =
                sign(
                        costs.stream()
                                .flatMap(
                                        group ->
                                                java.util.stream.Stream.of(
                                                        group.lowestCostPerWear(),
                                                        group.highestCostPerWear(),
                                                        group.neverWorn()))
                                .toList());
        return new HistoryInsights.CostPerWear(
                today(),
                includeArchived,
                pieces.size(),
                (int)
                        pieces.stream()
                                .filter(garment -> garment.metadata().purchasePrice() == null)
                                .count(),
                (int)
                        pieces.stream()
                                .filter(
                                        garment ->
                                                garment.metadata().purchasePrice() != null
                                                        && garment.metadata().purchaseCurrency()
                                                                == null)
                                .count(),
                costs.stream()
                        .map(
                                group ->
                                        new CurrencyCosts(
                                                group.currency(),
                                                group.pricedGarmentCount(),
                                                group.knownPurchaseTotal(),
                                                signed(group.lowestCostPerWear(), signed),
                                                signed(group.highestCostPerWear(), signed),
                                                signed(group.neverWorn(), signed)))
                        .toList(),
                List.of(
                        "Cost per wear is the recorded purchase price divided by max(recorded wear count, 1).",
                        "Pieces with no recorded wears show their purchase price and are listed separately.",
                        "Prices are grouped by their recorded currency; no currency conversion is assumed.",
                        "Missing prices and currencies are counted separately and excluded from currency rankings."));
    }

    public HistoryInsights.Forgotten forgotten(InsightSeason season, int limit) {
        var pieces = reviewed(false);
        var timezone = ZoneId.of(profiles.current().timezone());
        var today = LocalDate.ofInstant(clock.instant(), timezone);
        var ranked =
                pieces.stream()
                        .flatMap(garment -> ranker.rank(garment, today, timezone, season).stream())
                        .sorted(
                                Comparator.comparingDouble(ForgottenRanker.RankedPiece::score)
                                        .reversed()
                                        .thenComparing(item -> item.piece().garment(), NAME_ORDER))
                        .toList();
        var selected =
                ranked.stream().limit(limit).map(ForgottenRanker.RankedPiece::piece).toList();
        var signed = sign(List.of(selected.stream().map(ForgottenPiece::garment).toList()));
        return new HistoryInsights.Forgotten(
                today,
                season,
                ranker.minimumOwnershipDays(),
                ranker.minimumDaysSinceWear(),
                pieces.size(),
                ranked.size(),
                unknownLastWorn(pieces),
                selected.stream()
                        .map(
                                item ->
                                        new ForgottenPiece(
                                                signed.get(item.garment().id()),
                                                item.ownedSince(),
                                                item.ownershipBasis(),
                                                item.ownershipDays(),
                                                item.daysSinceLastWear(),
                                                item.seasonCompatibility(),
                                                item.reasons()))
                        .toList(),
                List.of(
                        "Ranks ownership time, time since recorded wear, wear frequency and selected-season tags.",
                        "Archived pieces are excluded; laundry, packed and lent pieces receive lower priority.",
                        "A missing purchase date uses the date added, which may understate ownership time.",
                        "Worn pieces with unknown last-worn dates are excluded because recency cannot be established.",
                        "Select your current season to apply season compatibility; location is not inferred."));
    }

    private List<GarmentDetails> reviewed(boolean includeArchived) {
        return garments.ownedDetails(garments.ownedIds()).stream()
                .filter(garment -> garment.processingStatus() == ProcessingStatus.READY)
                .filter(garment -> includeArchived || garment.status() != GarmentStatus.ARCHIVED)
                .toList();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(profiles.current().timezone()));
    }

    private int unknownLastWorn(List<GarmentDetails> pieces) {
        return (int)
                pieces.stream()
                        .filter(garment -> garment.wearCount() > 0 && garment.lastWornAt() == null)
                        .count();
    }

    private Map<UUID, GarmentDetails> sign(List<List<GarmentDetails>> lists) {
        var unique =
                lists.stream()
                        .flatMap(Collection::stream)
                        .collect(
                                Collectors.toMap(
                                        GarmentDetails::id,
                                        Function.identity(),
                                        (first, ignored) -> first));
        if (unique.isEmpty()) return Map.of();
        return presenter.page(new GarmentPage(List.copyOf(unique.values()), null)).items().stream()
                .collect(Collectors.toMap(GarmentDetails::id, Function.identity()));
    }

    private List<GarmentDetails> signed(
            List<GarmentDetails> pieces, Map<UUID, GarmentDetails> signed) {
        return pieces.stream().map(garment -> signed.get(garment.id())).toList();
    }
}
