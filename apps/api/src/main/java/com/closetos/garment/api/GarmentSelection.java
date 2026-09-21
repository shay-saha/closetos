package com.closetos.garment.api;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record GarmentSelection(String scope, ObjectNode query, List<UUID> garmentIds) {
    public GarmentSelection {
        if (scope == null || (query == null) == (garmentIds == null))
            throw new IllegalArgumentException(
                    "A selection requires rules or explicit membership.");
        query = query == null ? null : query.deepCopy();
        garmentIds = garmentIds == null ? null : List.copyOf(garmentIds);
    }
}
