package com.closetos.media.infrastructure;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Configuration(proxyBeanMethods = false)
class DownloadSigningConfiguration {
    @Bean
    MediaDownloadSigner mediaDownloadSigner(
            Clock clock,
            S3Presigner presigner,
            @Value("${closetos.media.delivery-mode:cloudfront}") String mode,
            @Value("${closetos.media.bucket:closetos}") String bucket,
            @Value("${closetos.media.cloudfront-domain:}") String domain,
            @Value("${closetos.media.cloudfront-key-id:}") String keyId,
            @Value("${closetos.media.cloudfront-private-key:}") String privateKey) {
        return switch (mode) {
            case "cloudfront" -> new CloudFrontDownloadSigner(clock, domain, keyId, privateKey);
            case "s3" ->
                    (key, expiresAt) ->
                            presigner
                                    .presignGetObject(
                                            GetObjectPresignRequest.builder()
                                                    .signatureDuration(
                                                            Duration.between(
                                                                    clock.instant(), expiresAt))
                                                    .getObjectRequest(
                                                            GetObjectRequest.builder()
                                                                    .bucket(bucket)
                                                                    .key(key)
                                                                    .build())
                                                    .build())
                                    .url()
                                    .toString();
            default -> throw new IllegalStateException("Choose cloudfront or s3 media delivery.");
        };
    }
}
