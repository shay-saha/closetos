package com.closetos.identity.application;

import com.closetos.identity.api.AccountRemovalAccess;
import com.closetos.identity.api.IdentityAccess;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class AccountRemovalRequests implements AccountRemovalAccess {
    private final JdbcClient jdbc;
    private final IdentityAccess identity;
    private final RevokedIdentities revocations;
    private final Clock clock;
    private final MeterRegistry metrics;

    AccountRemovalRequests(
            JdbcClient jdbc,
            IdentityAccess identity,
            RevokedIdentities revocations,
            Clock clock,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.revocations = revocations;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public RemovalRequest request() {
        String subject = AuthenticatedSubject.token().getSubject();
        revocations.lock(subject);
        var existing =
                jdbc.sql(
                                """
                SELECT id AS request_id, requested_at FROM account_removal
                WHERE subject_hash = encode(sha256(convert_to(CAST(:subject AS text), 'UTF8')), 'hex')
                """)
                        .param("subject", subject)
                        .query(RemovalRequest.class)
                        .optional();
        if (existing.isPresent()) return existing.get();
        UUID owner = identity.currentUserId();
        UUID request = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        var persisted =
                jdbc.sql(
                                """
                INSERT INTO account_removal (id, subject_hash, owner_id, provider_subject, requested_at, next_attempt_at)
                VALUES (:id, encode(sha256(convert_to(CAST(:subject AS text), 'UTF8')), 'hex'), :owner, :subject, :now, :now)
                RETURNING id AS request_id, requested_at
                """)
                        .param("id", request)
                        .param("owner", owner)
                        .param("subject", subject)
                        .param("now", now)
                        .query(RemovalRequest.class)
                        .single();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        metrics.counter("privacy.account.removal.requests").increment();
                    }
                });
        return persisted;
    }
}
