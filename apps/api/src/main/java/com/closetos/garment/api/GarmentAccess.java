package com.closetos.garment.api;

import com.closetos.media.api.ProcessingStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public interface GarmentAccess {
    void lockOwned(List<UUID> garments);

    List<UUID> ownedIds();

    void updateWearStatistics(UUID garment, int count, LocalDate lastWornOn);

    GarmentDetails patch(UUID garment, ObjectNode patch);

    GarmentDetails createDraft(UUID wardrobe);

    GarmentDetails owned(UUID garment);

    void processingState(UUID garment, UUID wardrobe, ProcessingStatus status);
}
