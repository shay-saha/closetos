package com.closetos.search.application;

import com.closetos.platform.api.OutboxEntry;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReembeddingJobTracker {
    private final JdbcClient jdbc;

    public ReembeddingJobTracker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<Checkpoint> checkpoint(OutboxEntry event) {
        if (!"REBUILD_EMBEDDING".equals(event.eventType())) return Optional.empty();
        return jdbc.sql(
                        "SELECT outcome, failure_code FROM reembedding_item WHERE event_id = :event AND outcome IS NOT NULL")
                .param("event", event.id())
                .query(Checkpoint.class)
                .optional();
    }

    @Transactional
    public void outcome(OutboxEntry event, String outcome, String failure) {
        if (!"REBUILD_EMBEDDING".equals(event.eventType())) return;
        jdbc.sql(
                        """
                UPDATE reembedding_item SET outcome = :outcome, failure_code = :failure
                WHERE event_id = :event AND outcome IS NULL AND EXISTS (SELECT 1 FROM outbox_event o
                    WHERE o.id = :event AND o.publish_attempts = :attempt AND o.published_at IS NULL)
                """)
                .param("event", event.id())
                .param("attempt", event.publishAttempts())
                .param("outcome", outcome)
                .param("failure", failure)
                .update();
    }

    public record Checkpoint(String outcome, String failureCode) {}
}
