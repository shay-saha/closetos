package com.closetos.search.application;

import com.closetos.platform.api.ActionLimitExceeded;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.platform.api.OutboxQueue;
import com.closetos.search.api.EmbeddingProviderPort;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EmbeddingGeneration {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingGeneration.class);
    private final EmbeddingInputLoader inputs;
    private final EmbeddingProviderPort provider;
    private final EmbeddingWorkRegistry registry;
    private final OutboxQueue queue;
    private final JsonMapper json;
    private final ReembeddingJobTracker jobs;

    public EmbeddingGeneration(
            EmbeddingInputLoader inputs,
            EmbeddingProviderPort provider,
            EmbeddingWorkRegistry registry,
            OutboxQueue queue,
            JsonMapper json,
            ReembeddingJobTracker jobs) {
        this.inputs = inputs;
        this.provider = provider;
        this.registry = registry;
        this.queue = queue;
        this.json = json;
        this.jobs = jobs;
    }

    public void generate(OutboxEntry event) {
        EmbeddingProviderPort.ModelInfo model = null;
        try {
            var checkpoint = jobs.checkpoint(event);
            if (checkpoint.isPresent()) {
                if ("FAILED".equals(checkpoint.get().outcome()))
                    queue.failed(event, checkpoint.get().failureCode(), true);
                else queue.published(event);
                return;
            }
            var payload = json.readTree(event.payload());
            UUID wardrobe = UUID.fromString(payload.path("wardrobeId").asText());
            var material = inputs.load(event.aggregateId(), wardrobe);
            if (material.isEmpty()) {
                jobs.outcome(event, "SKIPPED", null);
                queue.published(event);
                return;
            }
            model = provider.model();
            if ("REBUILD_EMBEDDING".equals(event.eventType())
                    && !model.modelKey().equals(payload.path("modelKey").asText()))
                throw new DomainException(
                        400,
                        "EMBEDDING_MODEL_CHANGED",
                        "The configured embedding model changed. Start a new rebuild.");
            if (!registry.begin(event, material.get(), model)) {
                jobs.outcome(event, "SUCCEEDED", null);
                queue.published(event);
                return;
            }
            var result = provider.embed(material.get().input(), model);
            if (registry.complete(event, material.get(), model, result)) queue.published(event);
            else queue.defer(event, Duration.ofSeconds(1));
        } catch (EmbeddingWorkRegistry.Busy exception) {
            queue.defer(event, Duration.ofSeconds(5));
        } catch (ActionLimitExceeded exception) {
            queue.defer(event, exception.retryAfter());
        } catch (RuntimeException exception) {
            boolean terminal =
                    event.publishAttempts() >= 5
                            || exception instanceof DomainException domain
                                    && domain.status() == 400;
            registry.failed(event, model, terminal);
            String failure =
                    exception instanceof DomainException domain
                                    && "EMBEDDING_MODEL_CHANGED".equals(domain.code())
                            ? "EMBEDDING_MODEL_CHANGED"
                            : "EMBEDDING_FAILURE";
            jobs.outcome(event, terminal ? "FAILED" : null, failure);
            queue.failed(event, failure, terminal);
            LOG.warn(
                    "Embedding job {} failed ({})",
                    event.id(),
                    exception.getClass().getSimpleName());
        }
    }
}
