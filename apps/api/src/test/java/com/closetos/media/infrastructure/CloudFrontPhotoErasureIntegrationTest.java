package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;

class CloudFrontPhotoErasureIntegrationTest {
    @Test
    void actualSdkUsesOneAccountBatchAndWaitsForTheGetConfirmation() throws Exception {
        UUID request = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String reference = "account-removal-" + request;
        String path = "/users/" + owner + "/*";
        var completed = new AtomicBoolean();
        var messages = new CopyOnWriteArrayList<Message>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    messages.add(
                            new Message(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().toString(),
                                    body,
                                    exchange.getRequestHeaders().getFirst("Authorization")));
                    String status = completed.get() ? "Completed" : "InProgress";
                    byte[] response =
                            ("""
                    <Invalidation xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Id>IERASURE123</Id><Status>%s</Status><CreateTime>2026-10-02T12:00:00Z</CreateTime>
                      <InvalidationBatch><CallerReference>%s</CallerReference>
                        <Paths><Quantity>1</Quantity><Items><Path>%s</Path></Items></Paths>
                      </InvalidationBatch>
                    </Invalidation>
                    """)
                                    .formatted(status, reference, path)
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(
                            "POST".equals(exchange.getRequestMethod()) ? 201 : 200,
                            response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                    exchange.close();
                });
        server.start();
        try (var client =
                CloudFrontClient.builder()
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create("local-test", "local-test")))
                        .endpointOverride(
                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                        .overrideConfiguration(
                                config -> config.apiCallTimeout(Duration.ofSeconds(5)))
                        .build()) {
            var erasure = new CloudFrontPhotoErasure(client, "EMEDIA123");
            assertThat(erasure.erase(request, owner)).isFalse();
            completed.set(true);
            assertThat(erasure.erase(request, owner)).isTrue();
        } finally {
            server.stop(0);
        }
        assertThat(messages).hasSize(4);
        assertThat(messages)
                .extracting(Message::method)
                .containsExactly("POST", "GET", "POST", "GET");
        for (var message : messages) {
            assertThat(message.authorization())
                    .startsWith("AWS4-HMAC-SHA256 ")
                    .contains("/us-east-1/cloudfront/aws4_request");
            if ("POST".equals(message.method())) {
                assertThat(message.path())
                        .isEqualTo("/2020-05-31/distribution/EMEDIA123/invalidation");
                assertThat(message.body())
                        .contains(
                                "<CallerReference>" + reference + "</CallerReference>",
                                "<Quantity>1</Quantity>",
                                "<Path>" + path + "</Path>");
            } else {
                assertThat(message.path())
                        .isEqualTo("/2020-05-31/distribution/EMEDIA123/invalidation/IERASURE123");
                assertThat(message.body()).isEmpty();
            }
        }
        assertThat(messages.get(0).body()).isEqualTo(messages.get(2).body());
    }

    private record Message(String method, String path, String body, String authorization) {}
}
