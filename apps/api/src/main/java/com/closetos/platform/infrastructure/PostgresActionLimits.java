package com.closetos.platform.infrastructure;

import com.closetos.platform.api.ActionLimitExceeded;
import com.closetos.platform.api.ExpensiveAction;
import com.closetos.platform.api.ExpensiveActionLimits;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostgresActionLimits implements ExpensiveActionLimits {
    private final JdbcClient jdbc;
    private final MeterRegistry metrics;

    public PostgresActionLimits(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public void consume(UUID wardrobe, ExpensiveAction action) {
        var policy =
                jdbc.sql("SELECT * FROM expensive_action_policy WHERE action = :action")
                        .param("action", action.name())
                        .query(Policy.class)
                        .single();
        jdbc.sql(
                        """
                INSERT INTO expensive_action_usage
                    (wardrobe_id, action, short_started_at, short_used, long_started_at, long_used)
                VALUES (:wardrobe, :action, to_timestamp(0), 0, to_timestamp(0), 0)
                ON CONFLICT (wardrobe_id, action) DO NOTHING
                """)
                .param("wardrobe", wardrobe)
                .param("action", action.name())
                .update();
        var usage =
                jdbc.sql(
                                """
                        SELECT short_started_at, short_used, long_started_at, long_used
                        FROM expensive_action_usage WHERE wardrobe_id = :wardrobe AND action = :action
                        FOR UPDATE
                        """)
                        .param("wardrobe", wardrobe)
                        .param("action", action.name())
                        .query(
                                (rs, row) ->
                                        new Usage(
                                                rs.getTimestamp("short_started_at").toInstant(),
                                                        rs.getInt("short_used"),
                                                rs.getTimestamp("long_started_at").toInstant(),
                                                        rs.getInt("long_used")))
                        .single();
        Instant now =
                jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        Instant shortStart = window(now, policy.shortWindowSeconds());
        Instant longStart = window(now, policy.longWindowSeconds());
        int shortUsed = shortStart.equals(usage.shortStartedAt()) ? usage.shortUsed() : 0;
        int longUsed = longStart.equals(usage.longStartedAt()) ? usage.longUsed() : 0;
        long retry = 0;
        if (shortUsed >= policy.shortLimit())
            retry =
                    shortStart.getEpochSecond()
                            + policy.shortWindowSeconds()
                            - now.getEpochSecond();
        if (longUsed >= policy.longLimit())
            retry =
                    Math.max(
                            retry,
                            longStart.getEpochSecond()
                                    + policy.longWindowSeconds()
                                    - now.getEpochSecond());
        if (retry > 0) {
            metrics.counter("expensive.actions.denied", "action", action.name()).increment();
            throw new ActionLimitExceeded(action, retry);
        }
        jdbc.sql(
                        """
                UPDATE expensive_action_usage
                SET short_started_at = :shortStart, short_used = :shortUsed,
                    long_started_at = :longStart, long_used = :longUsed
                WHERE wardrobe_id = :wardrobe AND action = :action
                """)
                .param("wardrobe", wardrobe)
                .param("action", action.name())
                .param("shortStart", Timestamp.from(shortStart))
                .param("shortUsed", shortUsed + 1)
                .param("longStart", Timestamp.from(longStart))
                .param("longUsed", longUsed + 1)
                .update();
    }

    private static Instant window(Instant now, int seconds) {
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
    }

    private record Policy(
            String action,
            int shortLimit,
            int shortWindowSeconds,
            int longLimit,
            int longWindowSeconds) {}

    private record Usage(
            Instant shortStartedAt, int shortUsed, Instant longStartedAt, int longUsed) {}
}
