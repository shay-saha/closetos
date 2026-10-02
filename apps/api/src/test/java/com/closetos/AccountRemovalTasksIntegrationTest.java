package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.closetos.identity.api.AccountRemovalAccess;
import com.closetos.identity.api.AccountRemovalTasks;
import com.closetos.identity.api.AccountRemovalTasks.Failure;
import com.closetos.identity.api.AccountRemovalTasks.Step;
import com.closetos.identity.api.AccountRemovalTasks.Work;
import com.closetos.identity.api.IdentityAccess;
import com.closetos.identity.application.PostgresAccountRemovalTasks;
import com.closetos.media.api.AccountPhotoStorage;
import com.closetos.media.api.MediaErasurePending;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.platform.api.AccountRemovalPreparation;
import com.closetos.platform.api.IdentityRevocations;
import com.closetos.platform.api.OutboxAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
    @Autowired List<AccountRemovalPreparation> preparations;
    @Autowired AccountPhotoStorage photos;
    @MockitoBean ObjectStoragePort storage;
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
                        new PostgresAccountRemovalTasks(
                                jdbc, clock, Duration.ofMinutes(16), preparations));
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
                        () ->
                                new PostgresAccountRemovalTasks(
                                        jdbc, clock, Duration.ofMinutes(10), preparations))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void photoSourcesSurviveTheCascadeIncludeDeletedImagesAndDisappearOnCompletion() {
        String subject = "photo-removal-" + UUID.randomUUID();
        UUID owner = as(subject, identity::currentUserId);
        UUID scope = wardrobe(owner);
        String current = photo(owner, scope);
        String historical = photoPrefix(owner) + "original.png";
        String discarded = photoPrefix(owner) + "original.heic";
        String removed = photoPrefix(owner) + "original.jpeg";
        String legacy = photoPrefix(owner);
        event(scope, "START_PROCESSING", Map.of("sourceKey", historical));
        event(scope, "START_PROCESSING", Map.of("sourceKey", current));
        event(scope, "DELETE_ORIGINAL", Map.of("sourceKey", discarded));
        event(
                scope,
                "DELETE_MEDIA",
                Map.of(
                        "sourceKey",
                        removed,
                        "prefix",
                        removed.substring(0, removed.lastIndexOf('/') + 1)));
        event(scope, "DELETE_MEDIA", Map.of("prefix", legacy));
        jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE wardrobe_id = :scope")
                .param("scope", scope)
                .update();
        UUID other = as("other-photo-" + UUID.randomUUID(), identity::currentUserId);
        UUID otherScope = wardrobe(other);
        String otherPhoto = photo(other, otherScope);
        event(otherScope, "START_PROCESSING", Map.of("sourceKey", otherPhoto));
        as(subject, requests::request);
        var work = tasks.eraseData(tasks.claim().orElseThrow()).orElseThrow();
        var expected = new java.util.HashSet<>(List.of(current, historical, discarded, removed));
        for (String extension : List.of("jpg", "jpeg", "png", "webp", "heic", "heif"))
            expected.add(legacy + "original." + extension);
        assertThat(sources(work)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(count("user_profile", owner)).isZero();
        assertThat(count("wardrobe", otherScope)).isEqualTo(1);
        assertThat(sources(tasks.eraseData(work).orElseThrow())).hasSize(10);
        tasks.checkpoint(work, Step.WORKERS).orElseThrow();
        tasks.checkpoint(work, Step.PROVIDER).orElseThrow();
        assertThat(tasks.defer(work, Failure.MEDIA_LINKS_ACTIVE, Duration.ofMinutes(16))).isTrue();
        now.set(work.mediaPurgeAfter());
        var resumed = tasks.claim().orElseThrow();
        var purged = tasks.checkpoint(resumed, Step.MEDIA).orElseThrow();
        assertThat(tasks.complete(purged)).isTrue();
        assertThat(sources(purged)).isEmpty();
    }

    @Test
    void malformedOrCrossOwnerCleanupSourcesRollBackErasureAndItsPreparedKeys() {
        String subject = "bad-photo-removal-" + UUID.randomUUID();
        UUID owner = as(subject, identity::currentUserId);
        UUID scope = wardrobe(owner);
        photo(owner, scope);
        UUID event =
                event(
                        scope,
                        "START_PROCESSING",
                        Map.of("sourceKey", photoPrefix(UUID.randomUUID()) + "original.png"));
        as(subject, requests::request);
        var work = tasks.claim().orElseThrow();
        assertThatThrownBy(() -> tasks.eraseData(work)).isInstanceOf(DataAccessException.class);
        assertThat(count("user_profile", owner)).isEqualTo(1);
        assertThat(sources(work)).isEmpty();
        assertThat(
                        jdbc.sql(
                                        "SELECT data_erased_at IS NULL FROM account_removal WHERE id = :id")
                                .param("id", work.requestId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
        jdbc.sql(
                        "UPDATE outbox_event SET payload = jsonb_build_object('sourceKey', :key::text) WHERE id = :id")
                .param("key", photoPrefix(owner) + "../original.png")
                .param("id", event)
                .update();
        assertThatThrownBy(() -> tasks.eraseData(work)).isInstanceOf(DataAccessException.class);
        assertThat(count("user_profile", owner)).isEqualTo(1);
        jdbc.sql("DELETE FROM outbox_event WHERE id = :id").param("id", event).update();
        assertThat(tasks.eraseData(work)).isPresent();
        assertThat(sources(work)).hasSize(1);
    }

    @Test
    void cleanupUsesFreshCommittedDataAndSurvivesAnOuterRepeatableReadRollback() {
        String subject = "snapshot-photo-removal-" + UUID.randomUUID();
        UUID owner = as(subject, identity::currentUserId);
        UUID scope = wardrobe(owner);
        as(subject, requests::request);
        var late = new AtomicReference<String>();
        var removed = new AtomicReference<Work>();
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(
                status -> {
                    assertThat(
                                    jdbc.sql(
                                                    "SELECT count(*) FROM garment_image WHERE user_id = :owner")
                                            .param("owner", owner)
                                            .query(Integer.class)
                                            .single())
                            .isZero();
                    var writer = new TransactionTemplate(transactions);
                    writer.setPropagationBehavior(
                            org.springframework.transaction.TransactionDefinition
                                    .PROPAGATION_REQUIRES_NEW);
                    writer.executeWithoutResult(inner -> late.set(photo(owner, scope)));
                    var work = tasks.claim().orElseThrow();
                    removed.set(tasks.eraseData(work).orElseThrow());
                    status.setRollbackOnly();
                });
        assertThat(count("user_profile", owner)).isZero();
        assertThat(sources(removed.get())).containsExactly(late.get());
        assertThat(
                        jdbc.sql(
                                        "SELECT data_erased_at IS NOT NULL AND lease_token = :token FROM account_removal WHERE id = :id")
                                .param("id", removed.get().requestId())
                                .param("token", removed.get().leaseToken())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    private List<String> sources(Work work) {
        return jdbc.sql("SELECT source_key FROM account_removal_source WHERE request_id = :id")
                .param("id", work.requestId())
                .query(String.class)
                .list();
    }

    private String photo(UUID owner, UUID scope) {
        UUID garment = UUID.randomUUID(), image = UUID.randomUUID();
        String key =
                "users/" + owner + "/garments/" + garment + "/images/" + image + "/original.png";
        jdbc.sql(
                        "INSERT INTO garment(id, wardrobe_id, name, category, created_at, updated_at) VALUES (:id, :scope, 'Private photo', 'TOP', now(), now())")
                .param("id", garment)
                .param("scope", scope)
                .update();
        jdbc.sql(
                        """
            INSERT INTO garment_image(id, garment_id, wardrobe_id, user_id, image_role,
                original_filename, mime_type, source_s3_key, expected_size, source_checksum,
                upload_key, processing_status)
            VALUES (:id, :garment, :scope, :owner, 'FRONT', 'private.png', 'image/png', :key,
                10, :checksum, :upload, 'AWAITING_UPLOAD')
            """)
                .param("id", image)
                .param("garment", garment)
                .param("scope", scope)
                .param("owner", owner)
                .param("key", key)
                .param("checksum", "A".repeat(43) + "=")
                .param("upload", UUID.randomUUID())
                .update();
        return key;
    }

    private String photoPrefix(UUID owner) {
        return "users/"
                + owner
                + "/garments/"
                + UUID.randomUUID()
                + "/images/"
                + UUID.randomUUID()
                + "/";
    }

    private UUID event(UUID scope, String type, Map<String, String> payload) {
        String key = "photo-removal:" + UUID.randomUUID();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                outbox.enqueue(
                                        scope, "image", UUID.randomUUID(), type, key, payload));
        return jdbc.sql("SELECT id FROM outbox_event WHERE idempotency_key = :key")
                .param("key", key)
                .query(UUID.class)
                .single();
    }

    @Test
    void accountStorageErasureRequiresWorkerDrainLinkExpiryAndTheCurrentLease() {
        String subject = "storage-removal-" + UUID.randomUUID();
        UUID owner = as(subject, identity::currentUserId);
        String key = photo(owner, wardrobe(owner));
        as(subject, requests::request);
        var work = tasks.claim().orElseThrow();
        assertThat(photos.erase(work.requestId(), owner, work.leaseToken())).isFalse();
        var erased = tasks.eraseData(work).orElseThrow();
        assertThat(photos.erase(work.requestId(), owner, work.leaseToken())).isFalse();
        tasks.checkpoint(erased, Step.WORKERS).orElseThrow();
        assertThat(photos.erase(work.requestId(), owner, work.leaseToken())).isFalse();
        tasks.defer(work, Failure.MEDIA_LINKS_ACTIVE, Duration.ofMinutes(16));
        now.set(erased.mediaPurgeAfter());
        var resumed = tasks.claim().orElseThrow();
        assertThat(photos.erase(resumed.requestId(), owner, work.leaseToken())).isFalse();
        assertThat(photos.erase(resumed.requestId(), UUID.randomUUID(), resumed.leaseToken()))
                .isFalse();
        assertThat(photos.erase(UUID.randomUUID(), owner, resumed.leaseToken())).isFalse();
        verifyNoInteractions(storage);
        assertThat(photos.erase(resumed.requestId(), owner, resumed.leaseToken())).isTrue();
        verify(storage).eraseOwner(owner, List.of(key));
        now.set(resumed.leaseUntil());
        assertThat(photos.erase(resumed.requestId(), owner, resumed.leaseToken())).isFalse();
        verifyNoMoreInteractions(storage);
    }

    @Test
    void storageErasureRejectsALeaseReplacedAfterItsCallersSnapshot() {
        request();
        var erased = tasks.eraseData(tasks.claim().orElseThrow()).orElseThrow();
        tasks.checkpoint(erased, Step.WORKERS).orElseThrow();
        tasks.defer(erased, Failure.MEDIA_LINKS_ACTIVE, Duration.ofMinutes(16));
        now.set(erased.mediaPurgeAfter());
        var first = tasks.claim().orElseThrow();
        var caller = new TransactionTemplate(transactions);
        caller.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        caller.executeWithoutResult(
                status -> {
                    assertThat(
                                    jdbc.sql(
                                                    "SELECT lease_token FROM account_removal WHERE id = :id")
                                            .param("id", first.requestId())
                                            .query(UUID.class)
                                            .single())
                            .isEqualTo(first.leaseToken());
                    assertThat(tasks.defer(first, Failure.MEDIA_PENDING, Duration.ZERO)).isTrue();
                    var replacement = replica.claim().orElseThrow();
                    assertThat(photos.erase(first.requestId(), first.ownerId(), first.leaseToken()))
                            .isFalse();
                    verifyNoInteractions(storage);
                    assertThat(
                                    photos.erase(
                                            replacement.requestId(),
                                            replacement.ownerId(),
                                            replacement.leaseToken()))
                            .isTrue();
                    status.setRollbackOnly();
                });
        verify(storage).eraseOwner(first.ownerId(), List.of());
    }

    @Test
    void aCheckpointCannotPermitPhotoErasureWhileTheProfileStillExists() {
        request();
        var work = tasks.claim().orElseThrow();
        jdbc.sql(
                        """
            UPDATE account_removal SET data_erased_at = :now::timestamptz - interval '16 minutes',
                media_purge_after = :now::timestamptz - interval '1 minute', workers_drained_at = :now
            WHERE id = :id
            """)
                .param("id", work.requestId())
                .param("now", now.get().atOffset(java.time.ZoneOffset.UTC))
                .update();
        assertThat(photos.erase(work.requestId(), work.ownerId(), work.leaseToken())).isFalse();
        verifyNoInteractions(storage);
    }

    @Test
    void unconfirmedStorageErasureCannotProduceASuccessfulCleanupStage() {
        request();
        var work = tasks.eraseData(tasks.claim().orElseThrow()).orElseThrow();
        tasks.checkpoint(work, Step.WORKERS).orElseThrow();
        tasks.defer(work, Failure.MEDIA_LINKS_ACTIVE, Duration.ofMinutes(16));
        now.set(work.mediaPurgeAfter());
        var resumed = tasks.claim().orElseThrow();
        doThrow(new MediaErasurePending()).when(storage).eraseOwner(any(), any());
        assertThatThrownBy(
                        () ->
                                photos.erase(
                                        resumed.requestId(),
                                        resumed.ownerId(),
                                        resumed.leaseToken()))
                .isInstanceOf(MediaErasurePending.class);
        assertThat(
                        jdbc.sql(
                                        "SELECT media_erased_at IS NULL FROM account_removal WHERE id = :id")
                                .param("id", resumed.requestId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
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
