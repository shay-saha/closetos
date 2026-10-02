package com.closetos.media.api;

import java.util.UUID;

public interface PhotoCachePort {
    boolean erase(UUID request, UUID owner);
}
