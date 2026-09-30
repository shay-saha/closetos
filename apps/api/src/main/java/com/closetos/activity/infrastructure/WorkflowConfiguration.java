package com.closetos.activity.infrastructure;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.sfn.SfnClient;

@Configuration
@EnableScheduling
class WorkflowConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "closetos.media.workflow-mode",
            havingValue = "aws",
            matchIfMissing = true)
    SfnClient sfnClient(
            AwsCredentialsProvider credentials,
            @Value("${closetos.aws.region:eu-west-2}") String region) {
        return SfnClient.builder()
                .credentialsProvider(credentials)
                .region(Region.of(region))
                .overrideConfiguration(
                        configuration ->
                                configuration
                                        .apiCallTimeout(Duration.ofSeconds(30))
                                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                                        .retryStrategy(
                                                StandardRetryStrategy.builder()
                                                        .maxAttempts(2)
                                                        .build()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "closetos.media.aws-consumer-enabled", havingValue = "true")
    EcsClient ecsClient(
            AwsCredentialsProvider credentials,
            @Value("${closetos.aws.region:eu-west-2}") String region) {
        return EcsClient.builder()
                .credentialsProvider(credentials)
                .region(Region.of(region))
                .overrideConfiguration(
                        configuration ->
                                configuration
                                        .apiCallTimeout(Duration.ofSeconds(30))
                                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                                        .retryStrategy(
                                                StandardRetryStrategy.builder()
                                                        .maxAttempts(2)
                                                        .build()))
                .build();
    }

    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("closetos-background-");
        return scheduler;
    }
}
