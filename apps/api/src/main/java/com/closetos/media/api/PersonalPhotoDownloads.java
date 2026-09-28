package com.closetos.media.api;

import java.time.Instant;
import java.util.UUID;

public interface PersonalPhotoDownloads {
    String sign(UUID owner, UUID garment, UUID image, String key, Instant expiresAt);
}
