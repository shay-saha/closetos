package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.closetos.identity.api.AccountRemovalAccess;
import com.closetos.identity.api.AccountRemovalTasks;
import com.closetos.identity.api.AccountRemovalTasks.Failure;
import com.closetos.identity.api.AccountRemovalTasks.Step;
import com.closetos.identity.api.AccountRemovalTasks.Work;
import com.closetos.identity.api.IdentityAccess;
import com.closetos.identity.application.PostgresAccountRemovalTasks;
import com.closetos.platform.api.IdentityRevocations;
import com.closetos.platform.api.OutboxAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class AccountRemovalTasksIntegrationTest extends PostgresIntegrationTest {
    @Autowired AccountRemovalAccess requests;
    @Autowired AccountRemovalTasks tasks;
    @Autowired IdentityAccess identity;
    @Autowired IdentityRevocations revocations;
    @Autowired JdbcClient jdbc;
    @Autowired OutboxAccess outbox;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private AccountRemovalTasks replica;

    @BeforeEach
    void independentReplicaWithControlledTime() {
        now.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        jdbc.sql("DELETE FROM account_removal").update();
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy =
                new ProxyFactory(
                        new PostgresAccountRemovalTasks(jdbc, clock, Duration.ofMinutes(16)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        replica = (AccountRemovalTasks) proxy.getProxy();
    }

    @Test
    void concurrentReplicasClaimOneRequestOnceAndCanRecoverAnExpiredLease() throws Exception {
        request();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var claims =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(
                                    index ->
                                            executor.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        assertThat(
                                                                        start.await(
                                                                                10,
                                                                                TimeUnit.SECONDS))
                                                                .isTrue();
                                                        return (index % 2 == 0 ? tasks : replica)
                                                                .claim();
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var owners = new java.util.ArrayList<Work>();
            for (var claim : claims) claim.get(10, TimeUnit.SECONDS).ifPresent(owners::add);
            assertThat(owners).hasSize(1);
            var first = owners.getFirst();
            assertThat(first.attemptCount()).isEqualTo(1);
            assertThat(replica.claim()).isEmpty();
            now.set(first.leaseUntil());
            var recovered = replica.claim().orElseThrow();
            assertThat(recovered.requestId()).isEqualTo(first.requestId());
            assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
            assertThat(recovered.attemptCount()).isEqualTo(2);
            assertThat(tasks.eraseData(first)).isEmpty();
            assertThat(tasks.defer(first, Failure.DEPENDENCY_UNAVAILABLE, Duration.ZERO)).isFalse();
            assertThat(tasks.complete(first)).isFalse();
        }
    }

    @Test
    void profileErasureAlsoRemovesOwnedDataAndQueuedWorkButPreservesAnotherAccount() {
        String subject = "removal-work-" + UUID.randomUUID();
        UUID owner = as(subject, identity::currentUserId);
        UUID wardrobe = wardrobe(owner);
        UUID other = as("other-" + UUID.randomUUID(), identity::currentUserId);
        UUID otherWardrobe = wardrobe(other);
        for (var scope : new UUID[] {wardrobe, otherWardrobe}) {
            jdbc.sql(
                            "INSERT INTO garment(id, wardrobe_id, name, category, created_at, updated_at) VALUES (:id, :wardrobe, 'Private piece', 'TOP', now(), now())")
                    .param("id", UUID.randomUUID())
                    .param("wardrobe", scope)
                    .update();
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status ->
                                    outbox.enqueue(
                                            scope,
                                            "garment",
                                            UUID.randomUUID(),
                                            "GENERATE_EMBEDDING",
                                            "removal-work:" + UUID.randomUUID(),
                                            Map.of("wardrobeId", scope)));
        }
        as(subject, requests::request);
        var work = tasks.claim().orElseThrow();
        var erased = tasks.eraseData(work).orElseThrow();
        assertThat(erased.dataErasedAt()).isEqualTo(now.get());
        assertThat(erased.mediaPurgeAfter()).isEqualTo(now.get().plus(Duration.ofMinutes(16)));
        assertThat(count("user_profile", owner)).isZero();
        assertThat(count("wardrobe", wardrobe)).isZero();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM outbox_event WHERE wardrobe_id = :wardrobe")
                                .param("wardrobe", wardrobe)
                                .query(Integer.class)
                                .single())
                .isZero();
        assertThat(count("user_profile", other)).isEqualTo(1);
        assertThat(count("wardrobe", otherWardrobe)).isEqualTo(1);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM garment WHERE wardrobe_id = :wardrobe")
                                .param("wardrobe", otherWardrobe)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        now.updateAndGet(instant -> instant.plusSeconds(1));
        assertThat(replica.eraseData(work).orElseThrow().dataErasedAt())
                .isEqualTo(erased.dataErasedAt());
        assertThat(revocations.revoked(subject)).isTrue();
    }

    @Test
    void checkpointsAndLinkExpiryAreRequiredBeforeTheReceiptCanComplete() {
        request();
        var work = tasks.claim().orElseThrow();
        assertThat(tasks.checkpoint(work, Step.WORKERS)).isEmpty();
        assertThat(tasks.checkpoint(work, Step.PROVIDER)).isEmpty();
        assertThat(tasks.checkpoint(work, Step.MEDIA)).isEmpty();
        assertThat(tasks.complete(work)).isFalse();
        var erased = tasks.eraseData(work).orElseThrow();
        assertThat(tasks.checkpoint(erased, Step.MEDIA)).isEmpty();
        var provider = tasks.checkpoint(erased, Step.PROVIDER).orElseThrow();
        var drained = tasks.checkpoint(provider, Step.WORKERS).orElseThrow();
        assertThat(tasks.checkpoint(drained, Step.MEDIA)).isEmpty();
        assertThat(tasks.complete(drained)).isFalse();
        assertThat(tasks.defer(drained, Failure.MEDIA_LINKS_ACTIVE, Duration.ofMinutes(16)))
                .isTrue();
        assertThat(replica.claim()).isEmpty();
        now.set(drained.mediaPurgeAfter());
        var resumed = replica.claim().orElseThrow();
        assertThat(resumed.dataErasedAt()).isEqualTo(erased.dataErasedAt());
        assertThat(resumed.providerErasedAt()).isEqualTo(provider.providerErasedAt());
        assertThat(resumed.workersDrainedAt()).isEqualTo(drained.workersDrainedAt());
        var purged = replica.checkpoint(resumed, Step.MEDIA).orElseThrow();
        assertThat(replica.complete(purged)).isTrue();
        assertThat(tasks.complete(drained)).isFalse();
        assertThat(tasks.claim()).isEmpty();
        assertThat(
                        jdbc.sql(
                                        "SELECT owner_id IS NULL AND provider_subject IS NULL AND lease_token IS NULL AND lease_until IS NULL AND failure_code IS NULL AND state = 'COMPLETE' FROM account_removal WHERE id = :id")
                                .param("id", work.requestId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(purged.toString())
                .doesNotContain(
                        purged.ownerId().toString(),
                        purged.providerSubject(),
                        purged.leaseToken().toString());
    }

    @Test
    void expiredAttemptsCannotEraseDataOrRecordProgressEvenBeforeAnotherReplicaClaims() {
        request();
        var work = tasks.claim().orElseThrow();
        now.set(work.leaseUntil());
        assertThat(tasks.eraseData(work)).isEmpty();
        assertThat(tasks.checkpoint(work, Step.PROVIDER)).isEmpty();
        assertThat(tasks.defer(work, Failure.PROVIDER_UNAVAILABLE, Duration.ZERO)).isFalse();
        assertThat(tasks.complete(work)).isFalse();
        assertThat(count("user_profile", work.ownerId())).isEqualTo(1);
    }

    @Test
    void aForgedOwnerOrSubjectCannotRedirectAnOtherwiseCurrentLease() {
        request();
        var work = tasks.claim().orElseThrow();
        for (var forged :
                new Work[] {
                    new Work(
                            work.requestId(),
                            UUID.randomUUID(),
                            work.providerSubject(),
                            work.attemptCount(),
                            work.leaseToken(),
                            work.leaseUntil(),
                            null,
                            null,
                            null,
                            null,
                            null),
                    new Work(
                            work.requestId(),
                            work.ownerId(),
                            "another-subject",
                            work.attemptCount(),
                            work.leaseToken(),
                            work.leaseUntil(),
                            null,
                            null,
                            null,
                            null,
                            null)
                }) {
            assertThat(tasks.eraseData(forged)).isEmpty();
            assertThat(tasks.defer(forged, Failure.DEPENDENCY_UNAVAILABLE, Duration.ZERO))
                    .isFalse();
        }
        assertThat(count("user_profile", work.ownerId())).isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsMissingStagesAndRewindingAnErasureCheckpoint() {
        request();
        var work = tasks.claim().orElseThrow();
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE account_removal SET state = 'COMPLETE', completed_at = now(), owner_id = NULL, provider_subject = NULL, lease_token = NULL, lease_until = NULL WHERE id = :id")
                                        .param("id", work.requestId())
                                        .update())
                .isInstanceOf(DataAccessException.class);
        tasks.eraseData(work).orElseThrow();
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE account_removal SET data_erased_at = NULL, media_purge_after = NULL WHERE id = :id")
                                        .param("id", work.requestId())
                                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE account_removal SET media_purge_after = NULL WHERE id = :id")
                                        .param("id", work.requestId())
                                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () -> new PostgresAccountRemovalTasks(jdbc, clock, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deferralPreservesCheckpointsAndUsesOnlyBoundedSanitizedFailureCodes() {
        request();
        var work = tasks.eraseData(tasks.claim().orElseThrow()).orElseThrow();
        assertThatThrownBy(() -> tasks.defer(work, Failure.MEDIA_PENDING, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tasks.defer(work, Failure.MEDIA_PENDING, Duration.ofDays(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.defer(work, Failure.MEDIA_PENDING, Duration.ofSeconds(30))).isTrue();
        assertThat(
                        jdbc.sql("SELECT failure_code FROM account_removal WHERE id = :id")
                                .param("id", work.requestId())
                                .query(String.class)
                                .single())
                .isEqualTo("MEDIA_PENDING");
        assertThat(tasks.claim()).isEmpty();
        now.updateAndGet(instant -> instant.plusSeconds(30));
        var resumed = replica.claim().orElseThrow();
        assertThat(resumed.dataErasedAt()).isEqualTo(work.dataErasedAt());
        assertThat(resumed.leaseToken()).isNotEqualTo(work.leaseToken());
    }

    private AccountRemovalAccess.RemovalRequest request() {
        return as("removal-work-" + UUID.randomUUID(), requests::request);
    }

    private UUID wardrobe(UUID owner) {
        UUID wardrobe = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO wardrobe(id, owner_id, name) VALUES (:id, :owner, 'Private wardrobe')")
                .param("id", wardrobe)
                .param("owner", owner)
                .update();
        return wardrobe;
    }

    private long count(String table, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
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
