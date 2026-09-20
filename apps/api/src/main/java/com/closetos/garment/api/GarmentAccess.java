package com.closetos.garment.api;

import com.closetos.media.api.ProcessingStatus;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public interface GarmentAccess {
    GarmentDetails patch(UUID garment, ObjectNode patch);

    GarmentDetails createDraft(UUID wardrobe);

    GarmentDetails owned(UUID garment);

    void processingState(UUID garment, UUID wardrobe, ProcessingStatus status);
}
