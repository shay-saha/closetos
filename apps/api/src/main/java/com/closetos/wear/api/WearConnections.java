package com.closetos.wear.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WearConnections {
    Map<UUID, Integer> wornTogether(UUID source, List<UUID> candidates);
}
