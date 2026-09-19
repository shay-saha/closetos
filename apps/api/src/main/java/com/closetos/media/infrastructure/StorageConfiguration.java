package com.closetos.media.infrastructure;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
class StorageConfiguration {
    @Bean
    AwsCredentialsProvider storageCredentials(
            @Value("${closetos.media.storage-mode:aws}") String mode,
            @Value("${closetos.media.local-access-key:}") String access,
            @Value("${closetos.media.local-secret-key:}") String secret) {
        if ("local".equals(mode)) {
            if (access.isBlank() || secret.isBlank())
                throw new IllegalStateException("Local object storage credentials are required");
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
        }
        if (!"aws".equals(mode)) throw new IllegalStateException("Unknown object storage mode");
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean
    S3Client s3Client(
            AwsCredentialsProvider credentials,
            @Value("${closetos.aws.region:eu-west-2}") String region,
            @Value("${closetos.media.endpoint:}") String endpoint) {
        var builder =
                S3Client.builder()
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(!endpoint.isBlank())
                                        .build())
                        .overrideConfiguration(
                                config -> config.apiCallTimeout(java.time.Duration.ofSeconds(20)));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(
            AwsCredentialsProvider credentials,
            @Value("${closetos.aws.region:eu-west-2}") String region,
            @Value("${closetos.media.endpoint:}") String endpoint) {
        var builder =
                S3Presigner.builder()
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(!endpoint.isBlank())
                                        .build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }
}
