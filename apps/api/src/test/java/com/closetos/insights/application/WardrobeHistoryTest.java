package com.closetos.insights.application;

import static com.closetos.insights.application.InsightFixtures.defaults;
import static com.closetos.insights.application.InsightFixtures.piece;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.identity.api.ProfileAccess;
import com.closetos.identity.api.ProfileDetails;
import com.closetos.insights.api.HistoryInsights.CurrencyCosts;
import com.closetos.insights.api.InsightSeason;
import com.closetos.media.api.ProcessingStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WardrobeHistoryTest {
    private final GarmentAccess garments = mock(GarmentAccess.class);
    private final GarmentPresenter presenter = mock(GarmentPresenter.class);
    private final ProfileAccess profiles = mock(ProfileAccess.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:30:00Z"), ZoneOffset.UTC);
    private final WardrobeHistory history =
            new WardrobeHistory(
                    garments, presenter, profiles, new ForgottenRanker(defaults()), clock);

    @BeforeEach
    void owner() {
        when(profiles.current())
                .thenReturn(
                        new ProfileDetails(
                                UUID.randomUUID(),
                                "Owner",
                                "en-GB",
                                "GBP",
                                "America/Los_Angeles",
                                false,
                                0));
        when(presenter.page(any()))
                .thenAnswer(invocation -> invocation.getArgument(0, GarmentPage.class));
    }

    @Test
    void usageReportsRecordedGarmentOccurrencesAndSeparatesUnwornAndUnknownHistory() {
        var never = piece("Never", 0, null, null);
        var worn = piece("Favourite", 10, "60", "GBP");
        var unknownDate =
                piece(
                        "Unknown date",
                        2,
                        null,
                        InsightFixtures.TODAY.minusDays(400),
                        List.of(),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        var archived =
                status(
                        piece("Archived", 100, null, null),
                        GarmentStatus.ARCHIVED,
                        ProcessingStatus.READY);
        var pending =
                status(
                        piece("Unreviewed", 500, null, null),
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY_FOR_REVIEW);
        load(List.of(never, worn, unknownDate, archived, pending));
        var usage = history.usage(false, 1);
        assertThat(usage.asOf()).isEqualTo("2026-09-30");
        assertThat(usage.garmentCount()).isEqualTo(3);
        assertThat(usage.garmentWearOccurrences()).isEqualTo(12);
        assertThat(usage.neverWornCount()).isEqualTo(1);
        assertThat(usage.unknownLastWornCount()).isEqualTo(1);
        assertThat(usage.mostWorn()).containsExactly(worn);
        assertThat(usage.leastWorn()).containsExactly(never);
        assertThat(usage.neverWorn()).containsExactly(never);
        assertThat(usage.availability()).containsEntry(GarmentStatus.AVAILABLE, 3);
        assertThat(usage.categories().values()).containsExactly(3);
        var all = history.usage(true, 100);
        assertThat(all.garmentCount()).isEqualTo(4);
        assertThat(all.garmentWearOccurrences()).isEqualTo(112);
        assertThat(all.mostWorn()).startsWith(archived);
        assertThat(all.availability()).containsEntry(GarmentStatus.ARCHIVED, 1);
    }

    @Test
    void monetaryRankingsKeepCurrenciesApartAndNeverWornPricesOutOfWornComparisons() {
        var pounds = piece("Low cost", 10, "50", "GBP");
        var high = piece("High cost", 2, "50", "GBP");
        var zero = piece("Gift", 5, "0", "GBP");
        var never = piece("Unworn purchase", 0, "75", "GBP");
        var dollars = piece("Dollar purchase", 1, "200", "USD");
        var missingPrice = piece("Unknown price", 1, null, "GBP");
        var missingCurrency = piece("Unknown currency", 1, "1000", null);
        load(List.of(dollars, never, pounds, high, zero, missingPrice, missingCurrency));
        var costs = history.costPerWear(false, 2);
        assertThat(costs.garmentCount()).isEqualTo(7);
        assertThat(costs.missingPriceCount()).isEqualTo(1);
        assertThat(costs.missingCurrencyCount()).isEqualTo(1);
        assertThat(costs.currencies())
                .extracting(CurrencyCosts::currency)
                .containsExactly("GBP", "USD");
        var gbp = costs.currencies().getFirst();
        assertThat(gbp.pricedGarmentCount()).isEqualTo(4);
        assertThat(gbp.knownPurchaseTotal()).isEqualByComparingTo("175");
        assertThat(gbp.lowestCostPerWear()).containsExactly(zero, pounds);
        assertThat(gbp.highestCostPerWear()).containsExactly(high, pounds);
        assertThat(gbp.neverWorn()).containsExactly(never);
        assertThat(gbp.neverWorn().getFirst().costPerWear()).isEqualByComparingTo("75");
        assertThat(costs.currencies().get(1).knownPurchaseTotal()).isEqualByComparingTo("200");
    }

    @Test
    void forgottenRanksEligiblePiecesAndDoesNotTurnMissingDatesIntoKnownRecency() {
        var unused = piece("Unused", 0, "20", "GBP");
        var regular = piece("Previously worn", 5, "30", "GBP");
        var laundry =
                status(
                        piece("Laundry", 0, "20", "GBP"),
                        GarmentStatus.LAUNDRY,
                        ProcessingStatus.READY);
        var noDate =
                piece(
                        "Unknown last wear",
                        2,
                        null,
                        InsightFixtures.TODAY.minusDays(400),
                        List.of("autumn"),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        var newer =
                piece(
                        "New purchase",
                        0,
                        null,
                        InsightFixtures.TODAY.minusDays(10),
                        List.of("autumn"),
                        GarmentStatus.AVAILABLE,
                        null,
                        null);
        var archived =
                status(
                        piece("Archive", 0, null, null),
                        GarmentStatus.ARCHIVED,
                        ProcessingStatus.READY);
        load(List.of(regular, newer, laundry, noDate, unused, archived));
        var forgotten = history.forgotten(InsightSeason.AUTUMN, 2);
        assertThat(forgotten.asOf()).isEqualTo("2026-09-30");
        assertThat(forgotten.garmentCount()).isEqualTo(5);
        assertThat(forgotten.eligibleCount()).isEqualTo(3);
        assertThat(forgotten.unknownLastWornCount()).isEqualTo(1);
        assertThat(forgotten.items())
                .extracting(item -> item.garment().id())
                .containsExactly(unused.id(), regular.id());
        assertThat(forgotten.items().getFirst().daysSinceLastWear()).isNull();
        assertThat(forgotten.items().getFirst().reasons()).contains("No wear has been recorded.");
    }

    @Test
    void emptyWardrobesHaveZeroCountsAndNoInventedMoneyOrFavourite() {
        load(List.of());
        var usage = history.usage(false, 12);
        assertThat(usage.garmentCount()).isZero();
        assertThat(usage.garmentWearOccurrences()).isZero();
        assertThat(usage.mostWorn()).isEmpty();
        assertThat(usage.leastWorn()).isEmpty();
        assertThat(usage.neverWorn()).isEmpty();
        assertThat(history.costPerWear(false, 12).currencies()).isEmpty();
        var forgotten = history.forgotten(null, 12);
        assertThat(forgotten.season()).isNull();
        assertThat(forgotten.eligibleCount()).isZero();
        assertThat(forgotten.items()).isEmpty();
    }

    @Test
    void tiedRanksRemainDeterministicWhenTheDatabaseReturnsADifferentOrder() {
        var a = piece("A shirt", 0, "20", "GBP");
        var b = piece("B shirt", 0, "20", "GBP");
        var c = piece("A shirt", 0, "20", "GBP");
        var pieces = new ArrayList<>(List.of(b, c, a));
        load(pieces);
        var first = history.forgotten(InsightSeason.AUTUMN, 2).items();
        Collections.reverse(pieces);
        load(pieces);
        assertThat(history.forgotten(InsightSeason.AUTUMN, 2).items()).isEqualTo(first);
        var expected =
                List.of(a, c).stream()
                        .sorted(java.util.Comparator.comparing(garment -> garment.id().toString()))
                        .toList();
        assertThat(history.usage(false, 2).leastWorn()).containsExactlyElementsOf(expected);
    }

    private void load(List<GarmentDetails> pieces) {
        var ids = pieces.stream().map(GarmentDetails::id).toList();
        when(garments.ownedIds()).thenReturn(ids);
        doAnswer(
                        invocation -> {
                            List<UUID> requested = invocation.getArgument(0);
                            assertThat(requested).containsExactlyElementsOf(ids);
                            return List.copyOf(pieces);
                        })
                .when(garments)
                .ownedDetails(anyList());
    }

    private GarmentDetails status(
            GarmentDetails piece, GarmentStatus status, ProcessingStatus processing) {
        return new GarmentDetails(
                piece.id(),
                piece.metadata(),
                status,
                processing,
                piece.wearCount(),
                piece.lastWornAt(),
                piece.costPerWear(),
                piece.version(),
                piece.createdAt(),
                piece.updatedAt(),
                piece.assets());
    }
}
