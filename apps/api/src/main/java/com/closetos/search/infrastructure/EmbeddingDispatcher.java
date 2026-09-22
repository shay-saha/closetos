package com.closetos.search.infrastructure;

import com.closetos.platform.api.OutboxQueue;
import com.closetos.search.application.EmbeddingGeneration;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "closetos.search.dispatch-enabled", havingValue = "true")
class EmbeddingDispatcher {
    private final OutboxQueue queue;
    private final EmbeddingGeneration generation;

    EmbeddingDispatcher(OutboxQueue queue, EmbeddingGeneration generation) {
        this.queue = queue;
        this.generation = generation;
    }

    @Scheduled(fixedDelayString = "${closetos.search.dispatch-ms:100}")
    void dispatch() {
        for (int batch = 0; batch < 8; batch++) {
            var events = queue.claim(Set.of("GENERATE_EMBEDDING"));
            if (events.isEmpty()) return;
            for (var event : events) generation.generate(event);
        }
    }
}
