package com.closetos.activity.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.closetos.media.api.WorkflowJob;
import com.closetos.platform.api.DomainException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LocalWorkflowOrchestratorTest {
    private HttpServer server;
    private LocalWorkflowOrchestrator workflow;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"drained\":true}";

    @BeforeEach
    void startWorker() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    method.set(exchange.getRequestMethod());
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        workflow =
                new LocalWorkflowOrchestrator(
                        JsonMapper.builder().build(),
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "private-worker-token");
    }

    @AfterEach
    void stopWorker() {
        server.stop(0);
    }

    @Test
    void ownerCancellationUsesAnAuthenticatedPostAndWaitsForExplicitDrainConfirmation() {
        UUID owner = UUID.randomUUID();
        body = "{\"drained\":false}";
        assertThat(workflow.cancelOwner(owner)).isFalse();
        assertThat(path.get()).isEqualTo("/owners/" + owner + "/cancel");
        assertThat(authorization.get()).isEqualTo("Bearer private-worker-token");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(requestBody.get()).isEmpty();
        body = "{\"drained\":true}";
        assertThat(workflow.cancelOwner(owner)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "[]",
                "{\"drained\":null}",
                "{\"drained\":\"true\"}",
                "{\"drained\":true,\"unknown\":1}",
                "invalid-json"
            })
    void aMissingOrMalformedResponseCannotConfirmCancellation(String response) {
        body = response;
        assertThatThrownBy(() -> workflow.cancelOwner(UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 410, 500, 503})
    void failedResponsesCannotConfirmCancellationOrExposeWorkerDiagnostics(int responseStatus) {
        status = responseStatus;
        body = "Private worker diagnostic";
        assertThatThrownBy(() -> workflow.cancelOwner(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Media worker cancellation is unavailable.")
                .hasNoCause();
    }

    @Test
    void anUnavailableWorkerCannotConfirmThatJobsHaveDrained() {
        server.stop(0);
        assertThatThrownBy(() -> workflow.cancelOwner(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelledLocalJobsAreClassifiedAsTerminalDomainFailures() {
        status = 410;
        body = "Processing was cancelled";
        UUID image = UUID.randomUUID(), garment = UUID.randomUUID();
        String prefix =
                "users/" + UUID.randomUUID() + "/garments/" + garment + "/images/" + image + "/";
        var job =
                new WorkflowJob(
                        UUID.randomUUID(),
                        image,
                        garment,
                        UUID.randomUUID(),
                        prefix + "original.png",
                        prefix + "pipelines/1-r1/",
                        "image/png",
                        10,
                        "checksum",
                        "1-r1",
                        "request");
        assertThatThrownBy(() -> workflow.start(job))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> {
                            assertThat(exception.status()).isEqualTo(410);
                            assertThat(exception.code()).isEqualTo("PROCESSING_CANCELLED");
                        });
    }
}
