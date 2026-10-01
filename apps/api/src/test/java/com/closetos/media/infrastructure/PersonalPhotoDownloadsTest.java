package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.closetos.platform.api.MediaSigningAdmission;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class PersonalPhotoDownloadsTest {
    private final Instant now = Instant.now();
    private final UUID owner = UUID.randomUUID(),
            garment = UUID.randomUUID(),
            image = UUID.randomUUID();
    private final String prefix =
            "users/" + owner + "/garments/" + garment + "/images/" + image + "/";
    private final MediaSigningAdmission admission =
            new MediaSigningAdmission() {
                @Override
                public <T> T sign(UUID owner, Supplier<T> operation) {
                    return operation.get();
                }
            };

    @Test
    void signsOwnedOriginalsAndDerivativesForPrivateUncachedDownloads() {
        try (var presigner = signer()) {
            var downloads =
                    new S3PersonalPhotoDownloads(
                            presigner, Clock.fixed(now, ZoneOffset.UTC), "closetos", admission);
            for (String tail :
                    new String[] {
                        "original.jpg",
                        "original.png",
                        "original.heic",
                        "pipelines/1-r1/isolated.webp",
                        "pipelines/1-r1/mask.png"
                    }) {
                URI url =
                        URI.create(
                                downloads.sign(
                                        owner,
                                        garment,
                                        image,
                                        prefix + tail,
                                        now.plusSeconds(900)));
                assertThat(url.getPath()).isEqualTo("/closetos/" + prefix + tail);
                assertThat(url.getRawQuery())
                        .contains(
                                "X-Amz-Signature=",
                                "X-Amz-Expires=900",
                                "response-cache-control=private%2C%20no-store",
                                "response-content-disposition=attachment");
            }
        }
    }

    @Test
    void rejectsCrossOwnerKeysManifestsTraversalAndExcessiveLifetimes() {
        try (var presigner = signer()) {
            var downloads =
                    new S3PersonalPhotoDownloads(
                            presigner, Clock.fixed(now, ZoneOffset.UTC), "closetos", admission);
            for (String key :
                    new String[] {
                        prefix.replace(owner.toString(), UUID.randomUUID().toString())
                                + "original.png",
                        prefix.replace(garment.toString(), UUID.randomUUID().toString())
                                + "original.png",
                        prefix.replace(image.toString(), UUID.randomUUID().toString())
                                + "original.png",
                        prefix + "../original.png",
                        prefix + "%2e%2e/original.png",
                        prefix + "pipelines/1-r1/analysis.json",
                        prefix + "original.html"
                    })
                assertThatThrownBy(
                                () ->
                                        downloads.sign(
                                                owner, garment, image, key, now.plusSeconds(900)))
                        .isInstanceOf(IllegalArgumentException.class);
            for (Instant expiry :
                    new Instant[] {null, now, now.minusSeconds(1), now.plusSeconds(901)})
                assertThatThrownBy(
                                () ->
                                        downloads.sign(
                                                owner,
                                                garment,
                                                image,
                                                prefix + "original.png",
                                                expiry))
                        .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private S3Presigner signer() {
        return S3Presigner.builder()
                .region(Region.EU_WEST_2)
                .endpointOverride(URI.create("http://localhost:9000"))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("local-test", "local-secret")))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
