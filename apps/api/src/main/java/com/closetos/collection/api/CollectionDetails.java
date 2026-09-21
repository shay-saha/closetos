package com.closetos.collection.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record CollectionDetails(
        UUID id,
        String name,
        CollectionType type,
        ObjectNode queryDefinition,
        List<UUID> garmentIds,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
