package com.closetos.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

class BedrockEmbeddingConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            EmbeddingConfiguration.class,
                            BedrockEmbeddingConfiguration.class,
                            WorkerEmbeddingClient.class)
                    .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                    .withBean(Clock.class, Clock::systemUTC)
                    .withBean(S3Client.class, () -> mock(S3Client.class))
                    .withBean(
                            AwsCredentialsProvider.class,
                            () ->
                                    StaticCredentialsProvider.create(
                                            AwsBasicCredentials.create(
                                                    "local-test", "local-test-secret")));

    @Test
    void leavesUnconfiguredSearchUnavailableAndSupportsTheLocalWorker() {
        context.run(
                application -> {
                    assertThat(application)
                            .hasNotFailed()
                            .hasSingleBean(EmbeddingProviderPort.class);
                    assertThatThrownBy(
                                    () -> application.getBean(EmbeddingProviderPort.class).model())
                            .isInstanceOf(DomainException.class);
                });
        context.withPropertyValues(
                        "closetos.search.worker-endpoint=http://localhost:8800",
                        "closetos.search.worker-token=local-test")
                .run(
                        application ->
                                assertThat(application)
                                        .hasNotFailed()
                                        .hasSingleBean(EmbeddingProviderPort.class)
                                        .getBean(EmbeddingProviderPort.class)
                                        .isInstanceOf(WorkerEmbeddingClient.class));
    }

    @Test
    void configuresExactlyOneProviderWithTheRegionalModelIdentity() {
        configured()
                .run(
                        application -> {
                            assertThat(application)
                                    .hasNotFailed()
                                    .hasSingleBean(EmbeddingProviderPort.class);
                            var model = application.getBean(EmbeddingProviderPort.class).model();
                            assertThat(model.model().provider()).isEqualTo("bedrock");
                            assertThat(model.model().dimensions()).isEqualTo(1024);
                            assertThat(model.model().modelId())
                                    .isEqualTo(
                                            "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1");
                        });
    }

    @Test
    void failsStartupWithMissingWrongOrAmbiguousModelConfiguration() {
        context.withPropertyValues("closetos.search.provider=bedrock")
                .run(application -> assertThat(application).hasFailed());
        for (String setting :
                new String[] {
                    "closetos.search.bedrock-dimensions=512",
                    "closetos.search.bedrock-region=eu-west-2",
                    "closetos.search.bedrock-model-id=amazon.titan-embed-text-v2:0",
                    "closetos.search.worker-endpoint=http://localhost:8800"
                }) {
            configured()
                    .withPropertyValues(setting, "closetos.search.worker-token=local-test")
                    .run(application -> assertThat(application).hasFailed());
        }
    }

    private ApplicationContextRunner configured() {
        return context.withPropertyValues(
                "closetos.search.provider=bedrock",
                "closetos.search.bedrock-region=us-east-1",
                "closetos.search.bedrock-model-id=arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1");
    }
}
