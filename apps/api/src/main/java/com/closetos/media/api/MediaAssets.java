package com.closetos.media.api;

import java.time.Instant;
import java.util.UUID;

public record MediaAssets(
        UUID imageId,
        String isolatedUrl,
        String displayUrl,
        String cardUrl,
        String thumbnailUrl,
        int width,
        int height,
        Instant expiresAt) {}
