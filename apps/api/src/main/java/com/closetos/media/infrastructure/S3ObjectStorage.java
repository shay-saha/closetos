package com.closetos.media.infrastructure;

import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.UploadInstructions;
import com.closetos.platform.api.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
class S3ObjectStorage implements ObjectStoragePort {
    private final S3Client s3;
    private final S3Presigner signer;
    private final Clock clock;
    private final String bucket;

    S3ObjectStorage(
            S3Client s3,
            S3Presigner signer,
            Clock clock,
            @Value("${closetos.media.bucket:closetos}") String bucket) {
        this.s3 = s3;
        this.signer = signer;
        this.clock = clock;
        this.bucket = bucket;
    }

    @Override
    public UploadInstructions signUpload(ImageRecord image) {
        var request =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(image.sourceS3Key())
                        .contentType(image.mimeType())
                        .contentLength(image.expectedSize())
                        .checksumSHA256(image.sourceChecksum())
                        .build();
        Duration lifetime = Duration.ofMinutes(10);
        var signed =
                signer.presignPutObject(
                        PutObjectPresignRequest.builder()
                                .signatureDuration(lifetime)
                                .putObjectRequest(request)
                                .build());
        return new UploadInstructions(
                signed.url().toString(),
                "PUT",
                Map.of(
                        "Content-Type",
                        image.mimeType(),
                        "x-amz-checksum-sha256",
                        image.sourceChecksum()),
                clock.instant().plus(lifetime));
    }

    @Override
    public String signDownload(String key, Instant expiresAt) {
        return signer.presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(Duration.between(clock.instant(), expiresAt))
                                .getObjectRequest(
                                        GetObjectRequest.builder().bucket(bucket).key(key).build())
                                .build())
                .url()
                .toString();
    }

    @Override
    public Optional<StoredObject> head(String key) {
        try {
            var result =
                    s3.headObject(
                            HeadObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .checksumMode(
                                            software.amazon.awssdk.services.s3.model.ChecksumMode
                                                    .ENABLED)
                                    .build());
            return Optional.of(
                    new StoredObject(
                            result.contentLength(), result.contentType(), result.checksumSHA256()));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return Optional.empty();
            throw exception;
        }
    }

    @Override
    public String readJson(String key) {
        StoredObject object =
                head(key).orElseThrow(() -> DomainException.notFound("Processing output"));
        if (object.size() > 128 * 1024)
            throw DomainException.invalid("Processing output is too large.");
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asUtf8String();
    }

    @Override
    public void deletePrefix(String prefix) {
        if (!prefix.matches("users/[0-9a-f-]{36}/garments/[0-9a-f-]{36}/images/[0-9a-f-]{36}/")) {
            throw DomainException.invalid("Invalid media prefix.");
        }
        for (var page :
                s3.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix))) {
            for (var object : page.contents()) delete(object.key());
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(bucket).key(key));
    }
}
