package com.closetos.insights.application;

import com.closetos.garment.api.GarmentAccess;
import com.closetos.garment.api.GarmentCategory;
import com.closetos.garment.api.GarmentStatus;
import com.closetos.insights.api.PairingContext;
import com.closetos.insights.api.RecommendationInsights.Duplicates;
import com.closetos.insights.api.RecommendationInsights.WorksWith;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.platform.api.DomainException;
import com.closetos.search.api.GarmentAffinities;
import com.closetos.wardrobe.api.WardrobeAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WardrobeRecommendations {
    private final GarmentAccess garments;
    private final WardrobeAccess wardrobes;
    private final GarmentAffinities affinities;
    private final RecommendationSnapshot snapshot;

    public WardrobeRecommendations(
            GarmentAccess garments,
            WardrobeAccess wardrobes,
            GarmentAffinities affinities,
            RecommendationSnapshot snapshot) {
        this.garments = garments;
        this.wardrobes = wardrobes;
        this.affinities = affinities;
        this.snapshot = snapshot;
    }

    public Duplicates duplicates(GarmentCategory category, int limit) {
        wardrobes.currentWardrobeId();
        return snapshot.duplicates(category, limit, affinities.currentModel().orElse(null));
    }

    public WorksWith worksWith(UUID source, PairingContext context, int limit) {
        validateSource(source);
        return snapshot.worksWith(source, context, limit, affinities.currentModel().orElse(null));
    }

    private void validateSource(UUID source) {
        var selected = garments.owned(source);
        if (selected.processingStatus() != ProcessingStatus.READY
                || selected.status() != GarmentStatus.AVAILABLE)
            throw new DomainException(
                    409,
                    "PAIRING_SOURCE_UNAVAILABLE",
                    "Choose a reviewed, available piece for pairing suggestions.");
    }
}
