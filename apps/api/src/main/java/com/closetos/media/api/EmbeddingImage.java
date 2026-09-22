package com.closetos.media.api;

import java.util.UUID;

public record EmbeddingImage(UUID imageId, String key, String checksumSha256) {}
