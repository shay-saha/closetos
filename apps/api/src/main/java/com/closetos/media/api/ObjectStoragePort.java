package com.closetos.media.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ObjectStoragePort {
    UploadInstructions signUpload(ImageRecord image);

    String signDownload(String key, Instant expiresAt);

    Optional<StoredObject> head(String key);

    String readJson(String key);

    void deletePrefix(String prefix);

    void delete(String key);

    void eraseOwner(UUID owner, Collection<String> originals);

    record StoredObject(long size, String mimeType, String checksumSha256) {}
}
