package com.closetos.media.api;

import java.util.UUID;

public interface AccountPhotoStorage {
    boolean erase(UUID request, UUID owner, UUID lease);
}
