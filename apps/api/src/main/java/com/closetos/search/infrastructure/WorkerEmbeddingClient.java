package com.closetos.search.infrastructure;

import com.closetos.platform.api.DomainException;
import com.closetos.search.api.EmbeddingProviderPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnProperty(name = "closetos.search.worker-endpoint")
public class WorkerEmbeddingClient implements EmbeddingProviderPort {
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
    private final JsonMapper json;
    private final URI endpoint;
    private final String token;
    private final Clock clock;
    private volatile CachedModel cached;

    public WorkerEmbeddingClient(
            JsonMapper json,
            Clock clock,
            @Value("${closetos.search.worker-endpoint}") String endpoint,
            @Value("${closetos.search.worker-token}") String token) {
        this.json = json;
        this.clock = clock;
        this.endpoint = URI.create(endpoint);
        this.token = token;
    }

    @Override
    public ModelInfo model() {
        var previous = cached;
        if (previous != null && previous.until().isAfter(clock.instant())) return previous.info();
        var request = request("/embedding-model").GET().timeout(Duration.ofSeconds(10)).build();
        var model = json.readValue(send(request), ModelInfo.class);
        cached = new CachedModel(model, clock.instant().plusSeconds(60));
        return model;
    }

    @Override
    public VectorResult embed(Input input, ModelInfo expected) {
        ObjectNode body = json.valueToTree(input);
        body.put("expectedModelKey", expected.modelKey());
        var request =
                request("/embed")
                        .timeout(Duration.ofSeconds(90))
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build();
        var result = json.readValue(send(request), VectorResult.class);
        if (!expected.modelKey().equals(result.modelKey())
                || !expected.model().equals(result.model()))
            throw new IllegalStateException("The worker returned a different embedding model.");
        return result;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(endpoint.resolve(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
    }

    private String send(HttpRequest request) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                cached = null;
                throw DomainException.conflict();
            }
            if (response.statusCode() == 422)
                throw DomainException.invalid("The embedding input could not be processed.");
            if (response.statusCode() != 200)
                throw new DomainException(
                        503,
                        "EMBEDDINGS_UNAVAILABLE",
                        "Semantic search is temporarily unavailable.");
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding inference was interrupted.", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("The embedding worker is unavailable.", exception);
        }
    }

    private record CachedModel(ModelInfo info, Instant until) {}
}
