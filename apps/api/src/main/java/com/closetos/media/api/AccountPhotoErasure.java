package com.closetos.media.api;

import java.util.UUID;

public interface AccountPhotoErasure {
    boolean erase(UUID request, UUID owner, UUID lease);
}
