package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.closetos.identity.api.AccountRemovalAccess;
import com.closetos.identity.api.IdentityAccess;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.MediaSigningAdmission;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class MediaSigningAdmissionIntegrationTest extends PostgresIntegrationTest {
    @Autowired MediaSigningAdmission signing;
    @Autowired IdentityAccess identity;
    @Autowired AccountRemovalAccess removals;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void removedOrMissingOwnersCannotInvokeASignerWhileAnActiveOwnerCan() {
        String subject = subject();
        UUID owner = as(subject, identity::currentUserId);
        assertThat(signing.sign(owner, () -> "private-photo-link")).isEqualTo("private-photo-link");
        as(subject, removals::request);
        var invoked = new AtomicBoolean();
        for (UUID unavailable : new UUID[] {owner, UUID.randomUUID()}) {
            assertThatThrownBy(
                            () ->
                                    signing.sign(
                                            unavailable,
                                            () -> {
                                                invoked.set(true);
                                                return "new-link";
                                            }))
                    .isInstanceOf(DomainException.class);
        }
        assertThat(invoked).isFalse();
    }

    @Test
    void firstUseCanSignForAProfileCreatedInsideTheSameUploadTransaction() {
        String subject = subject();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            UUID owner = as(subject, identity::currentUserId);
                            assertThat(signing.sign(owner, () -> "first-photo-link"))
                                    .isEqualTo("first-photo-link");
                        });
    }

    @Test
    void revocationWaitsForAnAdmittedSignerAndRejectsEveryLaterSigner() throws Exception {
        String subject = subject();
        UUID owner = as(subject, identity::currentUserId);
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String connectionName = "signing-removal-" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var photo =
                    executor.submit(
                            () ->
                                    signing.sign(
                                            owner,
                                            () -> {
                                                admitted.countDown();
                                                try {
                                                    assertThat(release.await(20, TimeUnit.SECONDS))
                                                            .isTrue();
                                                } catch (InterruptedException error) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(error);
                                                }
                                                return "admitted-photo-link";
                                            }));
            assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
            var removal =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        jdbc.queryForObject(
                                                                "SELECT set_config('application_name', ?, true)",
                                                                String.class,
                                                                connectionName);
                                                        return as(subject, removals::request);
                                                    }));
            try {
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    waiting =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock')",
                                            Boolean.class,
                                            connectionName));
                    if (waiting) break;
                    Thread.sleep(20);
                }
                assertThat(waiting)
                        .as("Revocation must serialize with admitted photo signing")
                        .isTrue();
            } finally {
                release.countDown();
            }
            assertThat(photo.get(20, TimeUnit.SECONDS)).isEqualTo("admitted-photo-link");
            assertThat(removal.get(20, TimeUnit.SECONDS)).isNotNull();
            assertThatThrownBy(() -> signing.sign(owner, () -> "late-photo-link"))
                    .isInstanceOf(DomainException.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void anOldRepeatableReadSnapshotCannotIssueFreshLinksForARemovedOwner() {
        String subject = subject();
        UUID owner = as(subject, identity::currentUserId);
        var invoked = new AtomicBoolean();
        var snapshot = new TransactionTemplate(transactions);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(
                        () ->
                                snapshot.executeWithoutResult(
                                        status -> {
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM identity_authentication",
                                                    Integer.class);
                                            try (var executor =
                                                    Executors.newSingleThreadExecutor()) {
                                                try {
                                                    executor.submit(
                                                                    () ->
                                                                            as(
                                                                                    subject,
                                                                                    removals
                                                                                            ::request))
                                                            .get(20, TimeUnit.SECONDS);
                                                } catch (Exception error) {
                                                    throw new IllegalStateException(error);
                                                }
                                            }
                                            signing.sign(
                                                    owner,
                                                    () -> {
                                                        invoked.set(true);
                                                        return "old-snapshot-link";
                                                    });
                                        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("40001");
        assertThat(invoked).isFalse();
    }

    private static String subject() {
        return "signing-" + UUID.randomUUID();
    }

    private static <T> T as(String subject, Supplier<T> operation) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new JwtAuthenticationToken(
                        Jwt.withTokenValue("test-token")
                                .header("alg", "RS256")
                                .subject(subject)
                                .build()));
        SecurityContextHolder.setContext(context);
        try {
            return operation.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
