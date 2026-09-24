package com.closetos.outfit.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface OutfitConnections {
    Map<UUID, Integer> savedTogether(UUID source, List<UUID> candidates);
}
