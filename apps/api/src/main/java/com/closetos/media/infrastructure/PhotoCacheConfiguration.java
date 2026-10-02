package com.closetos.media.infrastructure;

import com.closetos.media.api.PhotoCachePort;
import com.closetos.platform.api.DomainException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;

@Configuration(proxyBeanMethods = false)
class PhotoCacheConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "closetos.media.delivery-mode",
            havingValue = "cloudfront",
            matchIfMissing = true)
    CloudFrontClient cloudFrontClient(AwsCredentialsProvider credentials) {
        return CloudFrontClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(20)))
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "closetos.media.delivery-mode",
            havingValue = "cloudfront",
            matchIfMissing = true)
    PhotoCachePort cloudFrontPhotoCache(
            CloudFrontClient client,
            @Value("${closetos.media.cloudfront-distribution-id:}") String distribution) {
        return new CloudFrontPhotoErasure(client, distribution);
    }

    @Bean
    @ConditionalOnProperty(name = "closetos.media.delivery-mode", havingValue = "s3")
    PhotoCachePort directPhotoCache() {
        return (request, owner) -> {
            if (request == null || owner == null)
                throw DomainException.invalid("Invalid photo cleanup scope.");
            return true;
        };
    }
}
