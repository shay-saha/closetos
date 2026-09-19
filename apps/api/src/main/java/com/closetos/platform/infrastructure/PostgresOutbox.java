package com.closetos.platform.infrastructure;

import com.closetos.platform.api.OutboxAccess;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.platform.api.OutboxQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class PostgresOutbox implements OutboxAccess, OutboxQueue {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    PostgresOutbox(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(
            String aggregateType, UUID aggregateId, String eventType, String key, Object payload) {
        jdbc.sql(
                        """
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, idempotency_key, payload)
                VALUES (:id, :type, :aggregate, :event, :key, CAST(:payload AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("type", aggregateType)
                .param("aggregate", aggregateId)
                .param("event", eventType)
                .param("key", key)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }

    @Override
    @Transactional
    public List<OutboxEntry> claim() {
        return jdbc.sql(
                        """
                UPDATE outbox_event SET lease_until = :lease, publish_attempts = publish_attempts + 1
                WHERE id IN (SELECT id FROM outbox_event
                    WHERE published_at IS NULL AND available_at <= :now
                    AND (lease_until IS NULL OR lease_until < :now)
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, aggregate_id, event_type, payload::text, publish_attempts
                """)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param(
                        "lease",
                        clock.instant().atOffset(ZoneOffset.UTC).plus(Duration.ofMinutes(6)))
                .query(OutboxEntry.class)
                .list();
    }

    @Override
    @Transactional
    public void published(OutboxEntry event) {
        jdbc.sql(
                        "UPDATE outbox_event SET published_at = :now, lease_until = NULL WHERE id = :id AND publish_attempts = :attempt")
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("id", event.id())
                .param("attempt", event.publishAttempts())
                .update();
    }

    @Override
    @Transactional
    public void failed(OutboxEntry event, String detail, boolean permanent) {
        jdbc.sql(
                        """
                UPDATE outbox_event SET lease_until = NULL, failure_detail = :detail,
                    available_at = :retry, published_at = :terminal
                WHERE id = :id AND publish_attempts = :attempt
                """)
                .param("id", event.id())
                .param("attempt", event.publishAttempts())
                .param("detail", detail.substring(0, Math.min(detail.length(), 1000)))
                .param(
                        "retry",
                        clock.instant()
                                .atOffset(ZoneOffset.UTC)
                                .plusSeconds(
                                        Math.min(300, 1L << Math.min(event.publishAttempts(), 8))))
                .param("terminal", permanent ? clock.instant().atOffset(ZoneOffset.UTC) : null)
                .update();
    }
}
