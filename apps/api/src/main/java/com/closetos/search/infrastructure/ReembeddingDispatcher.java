package com.closetos.search.infrastructure;

import com.closetos.platform.api.OutboxQueue;
import com.closetos.search.application.EmbeddingGeneration;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "closetos.search.dispatch-enabled", havingValue = "true")
class ReembeddingDispatcher {
    private final OutboxQueue queue;
    private final EmbeddingGeneration generation;

    ReembeddingDispatcher(OutboxQueue queue, EmbeddingGeneration generation) {
        this.queue = queue;
        this.generation = generation;
    }

    @Scheduled(fixedDelayString = "${closetos.search.reembedding-dispatch-ms:500}")
    void dispatch() {
        for (int batch = 0; batch < 2; batch++) {
            var events = queue.claim(Set.of("REBUILD_EMBEDDING"));
            if (events.isEmpty()) return;
            for (var event : events) generation.generate(event);
        }
    }
}
