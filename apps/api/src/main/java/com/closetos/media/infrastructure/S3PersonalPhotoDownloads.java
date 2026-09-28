package com.closetos.media.infrastructure;

import com.closetos.media.api.PersonalPhotoDownloads;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
class S3PersonalPhotoDownloads implements PersonalPhotoDownloads {
    private final S3Presigner signer;
    private final Clock clock;
    private final String bucket;

    S3PersonalPhotoDownloads(
            S3Presigner signer,
            Clock clock,
            @Value("${closetos.media.bucket:closetos}") String bucket) {
        this.signer = signer;
        this.clock = clock;
        this.bucket = bucket;
    }

    @Override
    public String sign(UUID owner, UUID garment, UUID image, String key, Instant expiresAt) {
        String prefix = "users/" + owner + "/garments/" + garment + "/images/" + image + "/";
        if (key == null
                || !key.startsWith(prefix)
                || !key.substring(prefix.length())
                        .matches(
                                "original\\.(jpg|jpeg|png|webp|heic|heif)|pipelines/[0-9]+-r[1-5]/((isolated|display|card|thumbnail)\\.webp|mask\\.png)"))
            throw new IllegalArgumentException(
                    "Only the owner's retained photograph may be downloaded.");
        Duration lifetime =
                expiresAt == null ? Duration.ZERO : Duration.between(clock.instant(), expiresAt);
        if (lifetime.isNegative()
                || lifetime.isZero()
                || lifetime.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException(
                    "Photograph download links must expire within fifteen minutes.");
        return signer.presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(lifetime)
                                .getObjectRequest(
                                        GetObjectRequest.builder()
                                                .bucket(bucket)
                                                .key(key)
                                                .responseCacheControl("private, no-store")
                                                .responseContentDisposition("attachment")
                                                .build())
                                .build())
                .url()
                .toString();
    }
}
