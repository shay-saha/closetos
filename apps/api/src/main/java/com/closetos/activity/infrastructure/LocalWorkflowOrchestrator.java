package com.closetos.activity.infrastructure;

import com.closetos.media.api.ProcessingResult;
import com.closetos.media.api.WorkflowJob;
import com.closetos.media.api.WorkflowOrchestratorPort;
import com.closetos.platform.api.DomainException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "closetos.media.workflow-mode", havingValue = "local")
class LocalWorkflowOrchestrator implements WorkflowOrchestratorPort {
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
    private final JsonMapper json;
    private final String endpoint;
    private final String token;

    LocalWorkflowOrchestrator(
            JsonMapper json,
            @Value("${closetos.media.worker-endpoint:http://localhost:8800}") String endpoint,
            @Value("${closetos.media.worker-token}") String token) {
        this.json = json;
        this.endpoint = endpoint;
        this.token = token;
    }

    @Override
    public WorkflowStart start(WorkflowJob job) {
        var request =
                HttpRequest.newBuilder(URI.create(endpoint + "/process"))
                        .timeout(Duration.ofMinutes(5))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(job)))
                        .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 422)
                throw DomainException.invalid("This photograph could not be processed.");
            if (response.statusCode() != 200)
                throw new IllegalStateException("Media worker is unavailable");
            return new WorkflowStart(
                    "local:" + job.executionName(),
                    json.readValue(response.body(), ProcessingResult.class));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Media processing was interrupted", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Media worker is unavailable", exception);
        }
    }
}
