package com.closetos.outfit.api;

import java.util.UUID;

public interface OutfitAccess {
    OutfitDetails forWear(UUID id, long version);
}
