package com.closetos.media.application;

import com.closetos.media.api.UploadRequest;
import com.closetos.platform.api.DomainException;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UploadPolicy {
    private static final Map<String, String> EXTENSIONS =
            Map.of(
                    "image/jpeg",
                    "jpg",
                    "image/png",
                    "png",
                    "image/webp",
                    "webp",
                    "image/heic",
                    "heic",
                    "image/heif",
                    "heif");
    private final long maximumBytes;

    public UploadPolicy(@Value("${closetos.media.max-original-bytes:26214400}") long maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    public String extension(UploadRequest request) {
        if (!EXTENSIONS.containsKey(request.mimeType()))
            throw DomainException.invalid("Choose a JPEG, PNG, WebP or HEIC photograph.");
        if (request.size() <= 0 || request.size() > maximumBytes)
            throw DomainException.invalid("This photograph exceeds the upload size limit.");
        try {
            if (Base64.getDecoder().decode(request.checksumSha256()).length != 32)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw DomainException.invalid("A SHA-256 checksum is required.");
        }
        return EXTENSIONS.get(request.mimeType());
    }
}
