package com.closetos.media.infrastructure;

import java.time.Instant;

@FunctionalInterface
interface MediaDownloadSigner {
    String sign(String key, Instant expiresAt);
}
