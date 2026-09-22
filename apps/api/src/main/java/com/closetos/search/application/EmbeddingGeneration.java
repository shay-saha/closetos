package com.closetos.search.application;

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

    public EmbeddingGeneration(
            EmbeddingInputLoader inputs,
            EmbeddingProviderPort provider,
            EmbeddingWorkRegistry registry,
            OutboxQueue queue,
            JsonMapper json) {
        this.inputs = inputs;
        this.provider = provider;
        this.registry = registry;
        this.queue = queue;
        this.json = json;
    }

    public void generate(OutboxEntry event) {
        EmbeddingProviderPort.ModelInfo model = null;
        try {
            UUID wardrobe =
                    UUID.fromString(json.readTree(event.payload()).path("wardrobeId").asText());
            var material = inputs.load(event.aggregateId(), wardrobe);
            if (material.isEmpty()) {
                queue.published(event);
                return;
            }
            model = provider.model();
            if (!registry.begin(event, material.get(), model)) {
                queue.published(event);
                return;
            }
            var result = provider.embed(material.get().input(), model);
            if (registry.complete(event, material.get(), model, result)) queue.published(event);
            else queue.defer(event, Duration.ofSeconds(1));
        } catch (EmbeddingWorkRegistry.Busy exception) {
            queue.defer(event, Duration.ofSeconds(5));
        } catch (RuntimeException exception) {
            boolean terminal =
                    event.publishAttempts() >= 5
                            || exception instanceof DomainException domain
                                    && domain.status() == 400;
            registry.failed(event, model, terminal);
            queue.failed(event, "EMBEDDING_FAILURE", terminal);
            LOG.warn(
                    "Embedding job {} failed ({})",
                    event.id(),
                    exception.getClass().getSimpleName());
        }
    }
}
