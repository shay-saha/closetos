package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.closetos.platform.api.MediaSigningAdmission;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class DownloadSigningConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(DownloadSigningConfiguration.class)
                    .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                    .withBean(
                            MediaSigningAdmission.class,
                            () -> {
                                var admission = mock(MediaSigningAdmission.class);
                                doAnswer(call -> call.<Supplier<?>>getArgument(1).get())
                                        .when(admission)
                                        .sign(any(), any());
                                return admission;
                            })
                    .withBean(S3Presigner.class, () -> mock(S3Presigner.class));

    @Test
    void defaultCloudDeliveryFailsStartupWithoutSigningConfiguration() {
        context.run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("closetos.media.delivery-mode=public")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void cloudfrontConfigurationBindsAndStorageUsesItsSignedUrl() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String key = CloudFrontDownloadSignerTest.pem(generator.generateKeyPair());
        context.withPropertyValues(
                        "closetos.media.cloudfront-domain=d111example.cloudfront.net",
                        "closetos.media.cloudfront-key-id=KEXAMPLE123",
                        "closetos.media.cloudfront-private-key=" + key)
                .run(
                        application -> {
                            assertThat(application).hasNotFailed();
                            var signing = application.getBean(MediaDownloadSigner.class);
                            assertThat(signing).isInstanceOf(CloudFrontDownloadSigner.class);
                            var storage =
                                    new S3ObjectStorage(
                                            mock(S3Client.class),
                                            application.getBean(S3Presigner.class),
                                            signing,
                                            application.getBean(Clock.class),
                                            "media-bucket",
                                            application.getBean(MediaSigningAdmission.class));
                            String derivative =
                                    "users/00000000-0000-4000-8000-000000000001/garments/00000000-0000-4000-8000-000000000002/images/00000000-0000-4000-8000-000000000003/pipelines/1-r1/card.webp";
                            String url = storage.signDownload(derivative, NOW.plusSeconds(900));
                            assertThat(URI.create(url).getHost())
                                    .isEqualTo("d111example.cloudfront.net");
                            assertThat(url)
                                    .contains("Key-Pair-Id=KEXAMPLE123", "Hash-Algorithm=SHA256");
                        });
    }

    @Test
    void explicitDevelopmentDeliveryUsesAShortLivedPrivateS3Signature() {
        context.withPropertyValues(
                        "closetos.media.delivery-mode=s3", "closetos.media.bucket=local-media")
                .run(
                        application -> {
                            assertThat(application).hasNotFailed();
                            try (var realSigner =
                                    S3Presigner.builder()
                                            .region(Region.EU_WEST_2)
                                            .credentialsProvider(
                                                    StaticCredentialsProvider.create(
                                                            AwsBasicCredentials.create(
                                                                    "local-test",
                                                                    "local-test-secret")))
                                            .build()) {
                                var mocked = application.getBean(S3Presigner.class);
                                when(mocked.presignGetObject(
                                                any(
                                                        software.amazon.awssdk.services.s3.presigner
                                                                .model.GetObjectPresignRequest
                                                                .class)))
                                        .thenAnswer(
                                                call ->
                                                        realSigner.presignGetObject(
                                                                call.getArgument(
                                                                        0,
                                                                        software.amazon.awssdk
                                                                                .services.s3
                                                                                .presigner.model
                                                                                .GetObjectPresignRequest
                                                                                .class)));
                                URI url =
                                        URI.create(
                                                application
                                                        .getBean(MediaDownloadSigner.class)
                                                        .sign(
                                                                "private/card.webp",
                                                                NOW.plusSeconds(900)));
                                assertThat(url.getHost())
                                        .isEqualTo("local-media.s3.eu-west-2.amazonaws.com");
                                assertThat(url.getPath()).isEqualTo("/private/card.webp");
                                assertThat(url.getQuery())
                                        .contains("X-Amz-Expires=900", "X-Amz-Signature=");
                            }
                        });
    }
}
