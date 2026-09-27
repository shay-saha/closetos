package com.closetos.search.infrastructure;

import com.closetos.search.api.EmbeddingProviderPort;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@ConditionalOnProperty(name = "closetos.search.provider", havingValue = "bedrock")
class BedrockEmbeddingConfiguration {
    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(
            AwsCredentialsProvider credentials,
            @Value("${closetos.search.bedrock-region:eu-west-2}") String region) {
        return BedrockRuntimeClient.builder()
                .credentialsProvider(credentials)
                .region(Region.of(region))
                .overrideConfiguration(
                        config ->
                                config.apiCallTimeout(Duration.ofSeconds(60))
                                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                                        .retryStrategy(
                                                StandardRetryStrategy.builder()
                                                        .maxAttempts(2)
                                                        .build()))
                .build();
    }

    @Bean
    EmbeddingProviderPort bedrockEmbeddings(
            BedrockRuntimeClient client,
            S3Client storage,
            JsonMapper json,
            Environment environment,
            @Value("${closetos.media.bucket:closetos}") String bucket,
            @Value("${closetos.search.bedrock-model-id:}") String model,
            @Value("${closetos.search.bedrock-region:eu-west-2}") String region,
            @Value("${closetos.search.bedrock-dimensions:1024}") int dimensions) {
        if (environment.containsProperty("closetos.search.worker-endpoint"))
            throw new IllegalStateException("Choose a local embedding worker or Bedrock.");
        if (model.startsWith("arn:") && !model.split(":")[3].equals(region))
            throw new IllegalStateException(
                    "Embedding model and Bedrock client regions must match.");
        return new BedrockEmbeddingClient(client, storage, json, bucket, model, dimensions);
    }
}
