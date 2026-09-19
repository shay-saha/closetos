package com.closetos.media.api;

import java.time.Instant;
import java.util.Optional;

public interface ObjectStoragePort {
    UploadInstructions signUpload(ImageRecord image);

    String signDownload(String key, Instant expiresAt);

    Optional<StoredObject> head(String key);

    String readJson(String key);

    void deletePrefix(String prefix);

    void delete(String key);

    record StoredObject(long size, String mimeType, String checksumSha256) {}
}
