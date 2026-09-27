package com.closetos.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.closetos.search.api.EmbeddingProviderPort.Input;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

class BedrockEmbeddingWireTest {
    @Test
    void signsAndSerializesARealSdkRequestAgainstALocalEndpoint() throws Exception {
        var json = JsonMapper.builder().build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var captured = new AtomicReference<Request>();
        byte[] response =
                json.writeValueAsBytes(
                        Map.of("embedding", Collections.nCopies(256, 2), "inputTextTokenCount", 3));
        server.createContext(
                "/",
                exchange -> {
                    captured.set(
                            new Request(
                                    exchange.getRequestURI().getPath(),
                                    exchange.getRequestHeaders().getFirst("Authorization"),
                                    exchange.getRequestHeaders().getFirst("Content-Type"),
                                    exchange.getRequestBody().readAllBytes()));
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try (var sdk =
                BedrockRuntimeClient.builder()
                        .region(Region.US_EAST_1)
                        .endpointOverride(
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                "local-test", "local-test-secret")))
                        .overrideConfiguration(
                                config ->
                                        config.apiCallTimeout(Duration.ofSeconds(5))
                                                .retryStrategy(
                                                        StandardRetryStrategy.builder()
                                                                .maxAttempts(1)
                                                                .build()))
                        .build()) {
            String model =
                    "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1";
            var provider =
                    new BedrockEmbeddingClient(
                            sdk, mock(S3Client.class), json, "media", model, 256);
            var result =
                    provider.embed(
                            new Input("blue summer shirt", null, null, null), provider.model());
            assertThat(result.vector()).hasSize(256);
            assertThat(captured.get().path()).isEqualTo("/model/" + model + "/invoke");
            assertThat(captured.get().authorization())
                    .startsWith("AWS4-HMAC-SHA256 ")
                    .contains("Credential=local-test/", "/us-east-1/bedrock/aws4_request");
            assertThat(captured.get().contentType()).isEqualTo("application/json");
            assertThat(json.readTree(captured.get().body()).get("inputText").asText())
                    .isEqualTo("blue summer shirt");
        } finally {
            server.stop(0);
        }
    }

    private record Request(String path, String authorization, String contentType, byte[] body) {}
}
