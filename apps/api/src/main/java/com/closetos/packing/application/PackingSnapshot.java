package com.closetos.packing.application;

import com.closetos.garment.api.GarmentDetails;
import java.util.List;
import java.util.UUID;

public record PackingSnapshot(
        UUID id, long version, PackingPreparation preparation, List<GarmentDetails> wardrobe) {
    public PackingSnapshot {
        wardrobe = List.copyOf(wardrobe);
    }
}
