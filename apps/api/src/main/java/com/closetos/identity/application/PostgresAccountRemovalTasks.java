package com.closetos.identity.application;

import com.closetos.identity.api.AccountRemovalTasks;
import com.closetos.platform.api.AccountRemovalPreparation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostgresAccountRemovalTasks implements AccountRemovalTasks {
    private static final String COLUMNS =
            "id AS request_id, owner_id, provider_subject, attempt_count, lease_token, lease_until, data_erased_at, workers_drained_at, provider_erased_at, media_erased_at, media_purge_after";
    private static final String OWNED =
            "id = :id AND lease_token = :token AND owner_id = :owner AND provider_subject = :subject AND state = 'REMOVING' AND lease_until > :now";
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration mediaGrace;
    private final List<AccountRemovalPreparation> preparations;

    public PostgresAccountRemovalTasks(
            JdbcClient jdbc,
            Clock clock,
            @Value("${closetos.privacy.media-grace:PT16M}") Duration mediaGrace,
            List<AccountRemovalPreparation> preparations) {
        if (mediaGrace == null
                || mediaGrace.compareTo(Duration.ofMinutes(15)) < 0
                || mediaGrace.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException(
                    "Account removal must allow existing photo links to expire.");
        this.jdbc = jdbc;
        this.clock = clock;
        this.mediaGrace = mediaGrace;
        this.preparations = List.copyOf(preparations);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Work> claim() {
        var now = clock.instant();
        return jdbc.sql(
                        """
                UPDATE account_removal SET state = 'REMOVING', lease_token = :token, lease_until = :until,
                    attempt_count = attempt_count + 1
                WHERE id = (SELECT id FROM account_removal
                    WHERE state <> 'COMPLETE' AND next_attempt_at <= :now
                        AND (lease_until IS NULL OR lease_until <= :now)
                    ORDER BY next_attempt_at, requested_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
                """
                                + "RETURNING "
                                + COLUMNS)
                .param("token", UUID.randomUUID())
                .param("now", timestamp(now))
                .param("until", timestamp(now.plus(Duration.ofMinutes(6))))
                .query(Work.class)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Work> eraseData(Work work) {
        var current = owned(work);
        if (current.isEmpty() || current.get().dataErasedAt() != null) return current;
        var subject =
                jdbc.sql("SELECT cognito_sub FROM user_profile WHERE id = :owner FOR UPDATE")
                        .param("owner", work.ownerId())
                        .query(String.class)
                        .optional();
        if (subject.isPresent() && !subject.get().equals(work.providerSubject()))
            throw new IllegalStateException("Account removal does not match its profile.");
        for (var preparation : preparations) preparation.prepare(work.requestId(), work.ownerId());
        jdbc.sql("DELETE FROM user_profile WHERE id = :owner AND cognito_sub = :subject")
                .param("owner", work.ownerId())
                .param("subject", work.providerSubject())
                .update();
        // Start the grace period after profile deletion has waited for outstanding mutations.
        var erasedAt = clock.instant();
        return bind(
                        jdbc.sql(
                                "UPDATE account_removal SET data_erased_at = :erased, media_purge_after = :purge WHERE "
                                        + OWNED
                                        + " RETURNING "
                                        + COLUMNS),
                        work,
                        erasedAt)
                .param("erased", timestamp(erasedAt))
                .param("purge", timestamp(erasedAt.plus(mediaGrace)))
                .query(Work.class)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Work> checkpoint(Work work, Step step) {
        String column =
                switch (step) {
                    case WORKERS -> "workers_drained_at";
                    case PROVIDER -> "provider_erased_at";
                    case MEDIA -> "media_erased_at";
                };
        String prerequisite =
                step == Step.MEDIA
                        ? " AND workers_drained_at IS NOT NULL AND media_purge_after <= :now"
                        : "";
        return bind(
                        jdbc.sql(
                                "UPDATE account_removal SET "
                                        + column
                                        + " = coalesce("
                                        + column
                                        + ", :now) WHERE "
                                        + OWNED
                                        + " AND data_erased_at IS NOT NULL"
                                        + prerequisite
                                        + " RETURNING "
                                        + COLUMNS),
                        work,
                        clock.instant())
                .query(Work.class)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean defer(Work work, Failure failure, Duration delay) {
        if (failure == null
                || delay == null
                || delay.isNegative()
                || delay.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Use a bounded account removal retry.");
        var now = clock.instant();
        return bind(
                                jdbc.sql(
                                        "UPDATE account_removal SET state = 'PENDING', lease_token = NULL, lease_until = NULL, failure_code = :failure, next_attempt_at = :retry WHERE "
                                                + OWNED),
                                work,
                                now)
                        .param("failure", failure.name())
                        .param("retry", timestamp(now.plus(delay)))
                        .update()
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean complete(Work work) {
        return bind(
                                jdbc.sql(
                                        """
                UPDATE account_removal SET state = 'COMPLETE', owner_id = NULL, provider_subject = NULL,
                    lease_token = NULL, lease_until = NULL, failure_code = NULL, completed_at = :now
                """
                                                + "WHERE "
                                                + OWNED
                                                + " AND data_erased_at IS NOT NULL AND workers_drained_at IS NOT NULL AND provider_erased_at IS NOT NULL AND media_erased_at IS NOT NULL"),
                                work,
                                clock.instant())
                        .update()
                == 1;
    }

    private Optional<Work> owned(Work work) {
        return bind(
                        jdbc.sql(
                                "SELECT "
                                        + COLUMNS
                                        + " FROM account_removal WHERE "
                                        + OWNED
                                        + " FOR UPDATE"),
                        work,
                        clock.instant())
                .query(Work.class)
                .optional();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query, Work work, Instant now) {
        return query.param("id", work.requestId())
                .param("token", work.leaseToken())
                .param("owner", work.ownerId())
                .param("subject", work.providerSubject())
                .param("now", timestamp(now));
    }

    private static java.time.OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
