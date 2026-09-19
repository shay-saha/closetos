package com.closetos.garment.api;

import com.closetos.media.api.ProcessingStatus;
import java.util.UUID;

public interface GarmentAccess {
    GarmentDetails createDraft(UUID wardrobe);

    GarmentDetails owned(UUID garment);

    void processingState(UUID garment, UUID wardrobe, ProcessingStatus status);
}
