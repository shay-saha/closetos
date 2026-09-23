package com.closetos.garment.api;

import com.closetos.media.api.ProcessingStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public interface GarmentAccess {
    GarmentPage list(GarmentFilter filter);

    List<UUID> matchingIds(GarmentFilter filter, ObjectNode constraints);

    List<GarmentDetails> ownedDetails(List<UUID> garments);

    List<EmbeddingTarget> embeddingTargets(UUID wardrobe);

    Optional<GarmentDetails> embeddingSnapshot(UUID garment, UUID wardrobe);

    void validateSmartQuery(ObjectNode query);

    GarmentPage select(GarmentFilter filter, GarmentSelection selection);

    void lockOwned(List<UUID> garments);

    List<UUID> ownedIds();

    void updateWearStatistics(UUID garment, int count, LocalDate lastWornOn);

    GarmentDetails patch(UUID garment, ObjectNode patch);

    GarmentDetails createDraft(UUID wardrobe);

    GarmentDetails owned(UUID garment);

    void processingState(UUID garment, UUID wardrobe, ProcessingStatus status);
}
