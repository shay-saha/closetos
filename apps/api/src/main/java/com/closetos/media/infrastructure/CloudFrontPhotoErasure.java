package com.closetos.media.infrastructure;

import com.closetos.media.api.MediaErasurePending;
import com.closetos.media.api.PhotoCachePort;
import com.closetos.platform.api.DomainException;
import java.util.UUID;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.GetInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.Invalidation;
import software.amazon.awssdk.services.cloudfront.model.InvalidationBatch;

final class CloudFrontPhotoErasure implements PhotoCachePort {
    private final CloudFrontClient client;
    private final String distribution;

    CloudFrontPhotoErasure(CloudFrontClient client, String distribution) {
        if (distribution == null || !distribution.matches("[A-Z0-9]{1,64}"))
            throw new IllegalStateException("Provide the media CloudFront distribution ID.");
        this.client = client;
        this.distribution = distribution;
    }

    @Override
    public boolean erase(UUID request, UUID owner) {
        if (request == null || owner == null)
            throw DomainException.invalid("Invalid photo cleanup scope.");
        var batch =
                InvalidationBatch.builder()
                        .callerReference("account-removal-" + request)
                        .paths(paths -> paths.quantity(1).items("/users/" + owner + "/*"))
                        .build();
        try {
            var created =
                    client.createInvalidation(
                                    CreateInvalidationRequest.builder()
                                            .distributionId(distribution)
                                            .invalidationBatch(batch)
                                            .build())
                            .invalidation();
            validate(created, batch);
            var observed =
                    client.getInvalidation(
                                    GetInvalidationRequest.builder()
                                            .distributionId(distribution)
                                            .id(created.id())
                                            .build())
                            .invalidation();
            validate(observed, batch);
            if (!created.id().equals(observed.id())) throw new MediaErasurePending();
            return "Completed".equals(observed.status());
        } catch (SdkException exception) {
            throw new MediaErasurePending();
        }
    }

    private void validate(Invalidation invalidation, InvalidationBatch expected) {
        if (invalidation == null || invalidation.id() == null || invalidation.id().isBlank())
            throw new MediaErasurePending();
        var batch = invalidation.invalidationBatch();
        if (batch == null
                || !expected.callerReference().equals(batch.callerReference())
                || batch.paths() == null
                || !Integer.valueOf(1).equals(batch.paths().quantity())
                || !expected.paths().items().equals(batch.paths().items())
                || !("Completed".equals(invalidation.status())
                        || "InProgress".equals(invalidation.status())))
            throw new MediaErasurePending();
    }
}
