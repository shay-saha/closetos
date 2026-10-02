package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.closetos.media.api.PhotoCachePort;
import com.closetos.platform.api.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;

class PhotoCacheConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(PhotoCacheConfiguration.class)
                    .withBean(
                            AwsCredentialsProvider.class,
                            () ->
                                    StaticCredentialsProvider.create(
                                            AwsBasicCredentials.create(
                                                    "local-test", "local-test")));

    @Test
    void cloudDeliveryRequiresTheDistributionEvenWhenSigningIsConfigured() {
        context.run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("closetos.media.cloudfront-distribution-id=EMEDIA123")
                .run(
                        application -> {
                            assertThat(application)
                                    .hasNotFailed()
                                    .hasSingleBean(CloudFrontClient.class)
                                    .hasSingleBean(PhotoCachePort.class);
                            assertThat(application.getBean(PhotoCachePort.class))
                                    .isInstanceOf(CloudFrontPhotoErasure.class);
                            assertThat(
                                            application
                                                    .getBean(CloudFrontClient.class)
                                                    .serviceClientConfiguration()
                                                    .overrideConfiguration()
                                                    .apiCallTimeout())
                                    .hasValue(java.time.Duration.ofSeconds(20));
                        });
    }

    @Test
    void directStorageDoesNotCreateAnAwsCacheClient() {
        context.withPropertyValues("closetos.media.delivery-mode=s3")
                .run(
                        application -> {
                            assertThat(application)
                                    .hasNotFailed()
                                    .doesNotHaveBean(CloudFrontClient.class)
                                    .hasSingleBean(PhotoCachePort.class);
                            var cache = application.getBean(PhotoCachePort.class);
                            assertThat(cache.erase(UUID.randomUUID(), UUID.randomUUID())).isTrue();
                            assertThatThrownBy(() -> cache.erase(null, UUID.randomUUID()))
                                    .isInstanceOf(DomainException.class);
                        });
    }
}
