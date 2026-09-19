package com.closetos.media.api;

import java.time.Instant;
import java.util.Map;

public record UploadInstructions(
        String url, String method, Map<String, String> headers, Instant expiresAt) {}
