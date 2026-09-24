package com.closetos.insights.application;

import static com.closetos.insights.application.RecommendationFixtures.piece;
import static com.closetos.insights.application.RecommendationFixtures.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentDetails;
import com.closetos.garment.api.GarmentPage;
import com.closetos.garment.api.GarmentPresenter;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.InsightSeason;
import com.closetos.insights.api.PairingContext;
import com.closetos.insights.api.RecommendationInsights.EmbeddingState;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.outfit.api.OutfitConnections;
import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.GarmentAffinities;
import com.closetos.wardrobe.api.WardrobeAccess;
import com.closetos.wear.api.WearConnections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationSnapshotTest {
    private static final ModelInfo MODEL =
            new ModelInfo(
                    "8".repeat(64),
                    new EmbeddingModel("local-clip", "clip", "1", "multimodal-1", 512));
    private final GarmentAccess garments = mock(GarmentAccess.class);
    private final GarmentPresenter presenter = mock(GarmentPresenter.class);
    private final MediaAccess media = mock(MediaAccess.class);
    private final WardrobeAccess wardrobes = mock(WardrobeAccess.class);
    private final GarmentAffinities affinities = mock(GarmentAffinities.class);
    private final OutfitConnections outfits = mock(OutfitConnections.class);
    private final WearConnections wear = mock(WearConnections.class);
    private final RecommendationSnapshot snapshot =
            new RecommendationSnapshot(
                    garments,
                    presenter,
                    media,
                    wardrobes,
                    affinities,
                    outfits,
                    wear,
                    new DuplicateRanker(),
                    new PairingRanker());
    private final UUID wardrobe = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(wardrobes.currentWardrobeId()).thenReturn(wardrobe);
        when(presenter.page(any()))
                .thenAnswer(invocation -> invocation.getArgument(0, GarmentPage.class));
    }

    @Test
    void duplicatesRequireApprovedPhotosAndCompatibleColoursAndReportPendingCoverage() {
        var a = piece("Olive shirt", GarmentCategory.TOP, "#45664c");
        var b = piece("Sage shirt", GarmentCategory.TOP, "#4a6955");
        var blue = piece("Blue shirt", GarmentCategory.TOP, "#0000ff");
        var unknown = piece("Unknown colour", GarmentCategory.TOP, null);
        var noPhoto = piece("Manual shirt", GarmentCategory.TOP, "#45664c");
        var archive =
                state(
                        piece("Archive", GarmentCategory.TOP, "#45664c"),
                        GarmentStatus.ARCHIVED,
                        ProcessingStatus.READY);
        var review =
                state(
                        piece("Unreviewed", GarmentCategory.TOP, "#45664c"),
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY_FOR_REVIEW);
        var shoes = piece("Olive shoes", GarmentCategory.SHOES, "#45664c");
        load(List.of(a, b, blue, unknown, noPhoto, archive, review, shoes));
        var photos = Set.of(a.id(), b.id(), blue.id(), unknown.id(), shoes.id());
        when(media.approvedPhotoGarments(anyList(), eq(wardrobe))).thenReturn(photos);
        when(affinities.neighbours(anyList(), eq(MODEL), eq(10)))
                .thenAnswer(
                        invocation -> {
                            List<UUID> ids = invocation.getArgument(0);
                            var indexed = new java.util.HashSet<>(ids);
                            indexed.remove(unknown.id());
                            var links =
                                    ids.contains(a.id())
                                            ? List.of(
                                                    new GarmentAffinities.Link(a.id(), b.id(), .99),
                                                    new GarmentAffinities.Link(
                                                            a.id(), blue.id(), .99))
                                            : List.<GarmentAffinities.Link>of();
                            return new GarmentAffinities.Neighbours(Set.copyOf(indexed), links);
                        });
        var result = snapshot.duplicates(null, 12, MODEL);
        assertThat(result.reviewedGarmentCount()).isEqualTo(6);
        assertThat(result.photoGarmentCount()).isEqualTo(5);
        assertThat(result.missingColourCount()).isEqualTo(1);
        assertThat(result.candidatePairCount()).isEqualTo(1);
        assertThat(result.embeddings().state()).isEqualTo(EmbeddingState.PENDING);
        assertThat(result.embeddings().indexedCount()).isEqualTo(4);
        assertThat(result.items()).hasSize(1);
        assertThat(
                        Set.of(
                                result.items().getFirst().first().id(),
                                result.items().getFirst().second().id()))
                .isEqualTo(Set.of(a.id(), b.id()));
        verify(affinities, never()).currentModel();
    }

    @Test
    void overlappingCategoryAndLayerGroupsDoNotDuplicatePairs() {
        var a =
                piece(
                        "First cardigan",
                        GarmentCategory.TOP,
                        "cardigan",
                        "#444444",
                        null,
                        null,
                        List.of(),
                        List.of());
        var b =
                piece(
                        "Second cardigan",
                        GarmentCategory.TOP,
                        "cardigan",
                        "#444444",
                        null,
                        null,
                        List.of(),
                        List.of());
        load(List.of(a, b));
        when(media.approvedPhotoGarments(anyList(), eq(wardrobe)))
                .thenReturn(Set.of(a.id(), b.id()));
        when(affinities.neighbours(anyList(), eq(MODEL), eq(10)))
                .thenReturn(
                        new GarmentAffinities.Neighbours(
                                Set.of(a.id(), b.id()),
                                List.of(new GarmentAffinities.Link(a.id(), b.id(), 1))));
        var result = snapshot.duplicates(GarmentCategory.TOP, 1, MODEL);
        assertThat(result.items()).hasSize(1);
        assertThat(result.candidatePairCount()).isEqualTo(1);
        assertThat(result.embeddings().state()).isEqualTo(EmbeddingState.READY);
    }

    @Test
    void unavailableEmbeddingsNeverFallBackToPretendVisualDuplicates() {
        var a = piece("Photo piece", GarmentCategory.TOP, "#444444");
        var b = piece("Another photo piece", GarmentCategory.TOP, "#444444");
        load(List.of(a, b));
        when(media.approvedPhotoGarments(anyList(), eq(wardrobe)))
                .thenReturn(Set.of(a.id(), b.id()));
        var result = snapshot.duplicates(null, 12, null);
        assertThat(result.items()).isEmpty();
        assertThat(result.embeddings().state()).isEqualTo(EmbeddingState.UNAVAILABLE);
        assertThat(result.embeddings().model()).isNull();
        verify(affinities, never())
                .neighbours(anyList(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void hardEligiblePairingsAreFilteredBeforeReadingHistoryAndAffinity() {
        var source = piece("Top", GarmentCategory.TOP, null);
        var available = piece("Bottom", GarmentCategory.BOTTOM, null);
        var laundry =
                state(
                        piece("Laundry", GarmentCategory.BOTTOM, null),
                        GarmentStatus.LAUNDRY,
                        ProcessingStatus.READY);
        var conflicting = piece("Another top", GarmentCategory.TOP, null);
        var review =
                state(
                        piece("Review", GarmentCategory.BOTTOM, null),
                        GarmentStatus.AVAILABLE,
                        ProcessingStatus.READY_FOR_REVIEW);
        load(List.of(source, available, laundry, conflicting, review));
        when(garments.owned(source.id())).thenReturn(source);
        when(affinities.from(source.id(), List.of(available.id()), MODEL))
                .thenReturn(
                        new GarmentAffinities.SourceAffinities(
                                Set.of(source.id(), available.id()), Map.of(available.id(), .9)));
        when(wear.wornTogether(source.id(), List.of(available.id())))
                .thenReturn(Map.of(available.id(), 2));
        when(outfits.savedTogether(source.id(), List.of(available.id())))
                .thenReturn(Map.of(available.id(), 1));
        var result =
                snapshot.worksWith(source.id(), new PairingContext(null, null, null), 12, MODEL);
        assertThat(result.eligibleCount()).isEqualTo(1);
        assertThat(result.items())
                .extracting(item -> item.garment().id())
                .containsExactly(available.id());
        assertThat(result.items().getFirst().wornTogetherCount()).isEqualTo(2);
        assertThat(result.items().getFirst().savedTogetherCount()).isEqualTo(1);
        assertThat(result.embeddings().state()).isEqualTo(EmbeddingState.READY);
        verify(affinities).from(source.id(), List.of(available.id()), MODEL);
        verify(wear).wornTogether(source.id(), List.of(available.id()));
        verify(outfits).savedTogether(source.id(), List.of(available.id()));
    }

    @Test
    void providerOutagesRetainHonestHistorySuggestionsAndSourceMismatchProducesNoResults() {
        var source = piece("Top", GarmentCategory.TOP, null);
        var target = piece("Bottom", GarmentCategory.BOTTOM, null);
        load(List.of(source, target));
        when(garments.owned(source.id())).thenReturn(source);
        when(wear.wornTogether(any(), anyList())).thenReturn(Map.of(target.id(), 3));
        when(outfits.savedTogether(any(), anyList())).thenReturn(Map.of());
        var result =
                snapshot.worksWith(source.id(), new PairingContext(null, null, null), 12, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().semanticAffinityKnown()).isFalse();
        assertThat(result.items().getFirst().wornTogetherCount()).isEqualTo(3);
        assertThat(result.embeddings().state()).isEqualTo(EmbeddingState.UNAVAILABLE);
        var mismatch =
                snapshot.worksWith(
                        source.id(),
                        new PairingContext(InsightSeason.WINTER, null, null),
                        12,
                        null);
        assertThat(mismatch.sourceMatchesContext()).isFalse();
        assertThat(mismatch.items()).isEmpty();
        assertThat(mismatch.eligibleCount()).isZero();
    }

    @Test
    void inaccessibleAndUnavailableSourcesAreRejectedBeforeProviderDiscovery() {
        var orchestration = new WardrobeRecommendations(garments, wardrobes, affinities, snapshot);
        UUID missing = UUID.randomUUID();
        when(garments.owned(missing)).thenThrow(DomainException.notFound("Garment"));
        assertThatThrownBy(
                        () ->
                                orchestration.worksWith(
                                        missing, new PairingContext(null, null, null), 12))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.status()).isEqualTo(404));
        var source =
                state(
                        piece("Packed", GarmentCategory.TOP, null),
                        GarmentStatus.PACKED,
                        ProcessingStatus.READY);
        when(garments.owned(source.id())).thenReturn(source);
        assertThatThrownBy(
                        () ->
                                orchestration.worksWith(
                                        source.id(), new PairingContext(null, null, null), 12))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo("PAIRING_SOURCE_UNAVAILABLE"));
        verify(affinities, never()).currentModel();
    }

    @Test
    void sourceAvailabilityIsRecheckedAfterProviderDiscovery() {
        var source = piece("Available at first", GarmentCategory.TOP, null);
        when(garments.owned(source.id()))
                .thenReturn(source, state(source, GarmentStatus.PACKED, ProcessingStatus.READY));
        when(affinities.currentModel()).thenReturn(Optional.of(MODEL));
        var orchestration = new WardrobeRecommendations(garments, wardrobes, affinities, snapshot);
        assertThatThrownBy(
                        () ->
                                orchestration.worksWith(
                                        source.id(), new PairingContext(null, null, null), 12))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.status()).isEqualTo(409));
        verify(affinities, never()).from(any(), anyList(), any());
    }

    private void load(List<GarmentDetails> pieces) {
        var ids = pieces.stream().map(GarmentDetails::id).toList();
        when(garments.ownedIds()).thenReturn(ids);
        when(garments.ownedDetails(ids)).thenReturn(new ArrayList<>(pieces));
    }
}
