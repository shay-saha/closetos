package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.MediaAccess;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.media.api.UploadInstructions;
import com.closetos.platform.api.ActionLimitExceeded;
import com.closetos.platform.api.ExpensiveAction;
import com.closetos.platform.api.ExpensiveActionLimits;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.platform.infrastructure.PostgresActionLimits;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.application.EmbeddingGeneration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class ExpensiveActionLimitsIntegrationTest extends PostgresIntegrationTest {
    @Autowired ExpensiveActionLimits limits;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired MediaAccess media;
    @Autowired ProcessingAccess processing;
    @Autowired ProcessingTransitions transitions;
    @Autowired EmbeddingGeneration generation;
    @MockitoBean ObjectStoragePort storage;
    @MockitoBean EmbeddingProviderPort provider;
    private ExpensiveActionLimits replica;
    private UUID wardrobe;
    private String owner;

    @BeforeEach
    void independentReplicaAndOwner() throws Exception {
        owner = "limits-" + UUID.randomUUID();
        var response =
                mvc.perform(as(get("/api/v1/wardrobes/current"), owner))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        wardrobe =
                UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(new PostgresActionLimits(jdbc, new SimpleMeterRegistry()));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        replica = (ExpensiveActionLimits) proxy.getProxy();
        when(storage.signUpload(any()))
                .thenAnswer(
                        invocation -> {
                            ImageRecord image = invocation.getArgument(0);
                            return new UploadInstructions(
                                    "https://storage.test/" + image.sourceS3Key(),
                                    "PUT",
                                    Map.of(),
                                    Instant.now().plusSeconds(600));
                        });
        var model =
                new EmbeddingProviderPort.ModelInfo(
                        "a".repeat(64),
                        new EmbeddingModel(
                                "bedrock",
                                "amazon.titan-embed-image-v1",
                                "1",
                                "multimodal-1",
                                256));
        when(provider.model()).thenReturn(model);
        when(provider.embed(any(), any()))
                .thenReturn(
                        new EmbeddingProviderPort.VectorResult(
                                model.modelKey(),
                                model.model(),
                                java.util.stream.IntStream.range(0, 256)
                                        .mapToObj(i -> i == 0 ? 1.0 : 0.0)
                                        .toList()));
    }

    @AfterEach
    void restoreSharedPolicies() {
        jdbc.sql(
                        """
                UPDATE expensive_action_policy p SET short_limit = defaults.short_limit,
                    short_window_seconds = 60, long_limit = defaults.long_limit, long_window_seconds = 86400
                FROM (VALUES ('PHOTO_UPLOAD', 30, 200), ('PROCESSING_RETRY', 10, 40),
                    ('SEMANTIC_QUERY', 30, 500), ('GARMENT_EMBEDDING', 60, 1000))
                    AS defaults(action, short_limit, long_limit) WHERE p.action = defaults.action
                """)
                .update();
    }

    @Test
    void simultaneousRequestsAcrossReplicasCannotExceedTheSharedAllowance() throws Exception {
        policy(ExpensiveAction.SEMANTIC_QUERY, 4, 10);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                var instance = i % 2 == 0 ? limits : replica;
                attempts.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                    try {
                                        instance.consume(wardrobe, ExpensiveAction.SEMANTIC_QUERY);
                                        return true;
                                    } catch (ActionLimitExceeded exception) {
                                        assertThat(exception.retryAfter().toSeconds())
                                                .isBetween(1L, 3600L);
                                        return false;
                                    }
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int admitted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(4);
            assertThat(used(ExpensiveAction.SEMANTIC_QUERY)).isEqualTo(4);
        }
    }

    @Test
    void burstWindowsResetWithoutClearingTheDailyAllowance() {
        policy(ExpensiveAction.PHOTO_UPLOAD, 1, 2);
        limits.consume(wardrobe, ExpensiveAction.PHOTO_UPLOAD);
        assertThatThrownBy(() -> replica.consume(wardrobe, ExpensiveAction.PHOTO_UPLOAD))
                .isInstanceOf(ActionLimitExceeded.class);
        expireShortWindow(ExpensiveAction.PHOTO_UPLOAD);
        replica.consume(wardrobe, ExpensiveAction.PHOTO_UPLOAD);
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isEqualTo(2);
        expireShortWindow(ExpensiveAction.PHOTO_UPLOAD);
        assertThatThrownBy(() -> replica.consume(wardrobe, ExpensiveAction.PHOTO_UPLOAD))
                .isInstanceOfSatisfying(
                        ActionLimitExceeded.class,
                        exception ->
                                assertThat(exception.retryAfter().toSeconds())
                                        .isBetween(1L, 86400L));
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isEqualTo(2);
        jdbc.sql(
                        "UPDATE expensive_action_usage SET long_started_at = to_timestamp(0) WHERE wardrobe_id = :wardrobe")
                .param("wardrobe", wardrobe)
                .update();
        limits.consume(wardrobe, ExpensiveAction.PHOTO_UPLOAD);
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isEqualTo(1);
    }

    @Test
    void rolledBackReservationsDoNotSpendAllowanceAndLimitsFollowTheWardrobeLifetime() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        transaction -> {
                            limits.consume(wardrobe, ExpensiveAction.PHOTO_UPLOAD);
                            transaction.setRollbackOnly();
                        });
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isZero();
        limits.consume(wardrobe, ExpensiveAction.PHOTO_UPLOAD);
        limits.consume(wardrobe, ExpensiveAction.SEMANTIC_QUERY);
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isEqualTo(1);
        assertThat(used(ExpensiveAction.SEMANTIC_QUERY)).isEqualTo(1);
        jdbc.sql("DELETE FROM wardrobe WHERE id = :wardrobe").param("wardrobe", wardrobe).update();
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isZero();
        assertThat(used(ExpensiveAction.SEMANTIC_QUERY)).isZero();
    }

    @Test
    void uploadReplaysRemainAvailableAtTheLimitAndDenialsLeaveNoDraftOrSignature()
            throws Exception {
        policy(ExpensiveAction.PHOTO_UPLOAD, 2, 3);
        UUID key = UUID.randomUUID();
        var first = upload(owner, key, "image/png", 1024, 201);
        upload(owner, UUID.randomUUID(), "image/png", 1024, 201);
        var replay = upload(owner, key, "image/png", 1024, 201);
        assertThat(replay.path("imageId").asText()).isEqualTo(first.path("imageId").asText());
        var denied = upload(owner, UUID.randomUUID(), "image/png", 1024, 429);
        assertThat(denied.path("code").asText()).isEqualTo("ACTION_LIMIT");
        assertThat(denied.path("detail").asText()).contains("photo upload", "Try again");
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isEqualTo(2);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM garment WHERE wardrobe_id = :wardrobe")
                                .param("wardrobe", wardrobe)
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
        verify(storage, times(3)).signUpload(any());
        upload(owner + "-other", UUID.randomUUID(), "image/png", 1024, 201);
    }

    @Test
    void invalidAndUnownedUploadsDoNotConsumeAllowance() throws Exception {
        upload(owner, UUID.randomUUID(), "application/pdf", 1024, 400);
        upload(owner, UUID.randomUUID(), "image/png", 26_214_401, 400);
        mvc.perform(
                        as(post("/api/v1/garments/" + UUID.randomUUID() + "/images"), owner)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(uploadBody("image/png", 1024)))
                .andExpect(status().isNotFound());
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isZero();
        verify(storage, never()).signUpload(any());
    }

    @Test
    void theDefaultAllowanceSupportsTwentyPhotoClosetScans() throws Exception {
        for (int i = 0; i < 20; i++) upload(owner, UUID.randomUUID(), "image/png", 1024, 201);
        assertThat(used(ExpensiveAction.PHOTO_UPLOAD)).isEqualTo(20);
    }

    @Test
    void rejectedRetriesPreserveTheFailedImageAndDoNotEnqueueWork() throws Exception {
        policy(ExpensiveAction.PROCESSING_RETRY, 1, 1);
        limits.consume(wardrobe, ExpensiveAction.PROCESSING_RETRY);
        var upload = upload(owner, UUID.randomUUID(), "image/png", 1024, 201);
        UUID imageId = UUID.fromString(upload.path("imageId").asText());
        var image = media.ownedImage(imageId, wardrobe);
        transitions.uploaded(image, "limits:" + imageId);
        var job =
                jdbc.sql("SELECT id FROM processing_job WHERE image_id = :image")
                        .param("image", imageId)
                        .query(UUID.class)
                        .single();
        transitions.failed(job, "PROCESSING_FAILURE", "Retry this photograph.");
        mvc.perform(as(post("/api/v1/garments/" + image.garmentId() + "/processing/retry"), owner))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertThat(processing.snapshot(imageId, wardrobe).state())
                .isEqualTo(ProcessingStatus.FAILED);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM processing_job WHERE image_id = :image")
                                .param("image", imageId)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void cachedSearchAndOrdinaryCatalogueStayUsableAfterTheInferenceLimitIsReached()
            throws Exception {
        policy(ExpensiveAction.SEMANTIC_QUERY, 1, 1);
        search("quiet knit", 200);
        search("quiet knit", 200);
        search("different silhouette", 429);
        search("different silhouette", 429);
        search("quiet knit", 200);
        mvc.perform(as(get("/api/v1/search").param("q", "knit").param("mode", "KEYWORD"), owner))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/garments"), owner)).andExpect(status().isOk());
        assertThat(used(ExpensiveAction.SEMANTIC_QUERY)).isEqualTo(1);
        verify(provider, times(1)).embed(any(), any());
    }

    @Test
    void failedInferenceAttemptsStillConsumeTheDurableAllowance() throws Exception {
        policy(ExpensiveAction.SEMANTIC_QUERY, 2, 2);
        when(provider.embed(any(), any()))
                .thenThrow(new IllegalStateException("Inference unavailable"));
        search("quiet knit", 500);
        search("quiet knit", 500);
        search("quiet knit", 429);
        assertThat(used(ExpensiveAction.SEMANTIC_QUERY)).isEqualTo(2);
        verify(provider, times(2)).embed(any(), any());
    }

    @Test
    void embeddingQuotaDefersWorkWithoutSpendingFailureAttemptsOrFailingTheGarment()
            throws Exception {
        policy(ExpensiveAction.GARMENT_EMBEDDING, 1, 1);
        limits.consume(wardrobe, ExpensiveAction.GARMENT_EMBEDDING);
        var response =
                mvc.perform(
                                as(post("/api/v1/garments"), owner)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"Quiet knit\",\"category\":\"TOP\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        UUID garment =
                UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
        var event =
                jdbc.sql(
                                """
                UPDATE outbox_event SET publish_attempts = 1, lease_until = now() + interval '6 minutes'
                WHERE aggregate_id = :garment AND event_type = 'GENERATE_EMBEDDING'
                RETURNING id, aggregate_id, event_type, payload::text, publish_attempts
                """)
                        .param("garment", garment)
                        .query(OutboxEntry.class)
                        .single();
        generation.generate(event);
        var state =
                jdbc.sql(
                                """
                SELECT publish_attempts, lease_until, published_at, failure_detail,
                    available_at > now() AS deferred FROM outbox_event WHERE id = :id
                """)
                        .param("id", event.id())
                        .query()
                        .singleRow();
        assertThat(state.get("publish_attempts")).isEqualTo(0);
        assertThat(state.get("lease_until")).isNull();
        assertThat(state.get("published_at")).isNull();
        assertThat(state.get("failure_detail")).isNull();
        assertThat(state.get("deferred")).isEqualTo(true);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM garment_embedding_work WHERE garment_id = :id")
                                .param("id", garment)
                                .query(Integer.class)
                                .single())
                .isZero();
        verify(provider, never()).embed(any(), any());
        mvc.perform(as(get("/api/v1/garments/" + garment), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("READY"));
        jdbc.sql(
                        """
                UPDATE expensive_action_usage SET short_started_at = to_timestamp(0),
                    long_started_at = to_timestamp(0) WHERE wardrobe_id = :wardrobe
                """)
                .param("wardrobe", wardrobe)
                .update();
        jdbc.sql(
                        "UPDATE outbox_event SET publish_attempts = 1, lease_until = now() + interval '6 minutes' WHERE id = :id")
                .param("id", event.id())
                .update();
        generation.generate(event);
        generation.generate(event);
        assertThat(used(ExpensiveAction.GARMENT_EMBEDDING)).isEqualTo(1);
        verify(provider, times(1)).embed(any(), any());
        assertThat(
                        jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = :id")
                                .param("id", event.id())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    private void policy(ExpensiveAction action, int shortLimit, int longLimit) {
        jdbc.sql(
                        """
                UPDATE expensive_action_policy SET short_limit = :shortLimit,
                    short_window_seconds = 3600, long_limit = :longLimit WHERE action = :action
                """)
                .param("action", action.name())
                .param("shortLimit", shortLimit)
                .param("longLimit", longLimit)
                .update();
    }

    private void expireShortWindow(ExpensiveAction action) {
        jdbc.sql(
                        "UPDATE expensive_action_usage SET short_started_at = to_timestamp(0) WHERE wardrobe_id = :wardrobe AND action = :action")
                .param("wardrobe", wardrobe)
                .param("action", action.name())
                .update();
    }

    private int used(ExpensiveAction action) {
        return jdbc.sql(
                        "SELECT coalesce(sum(long_used), 0) FROM expensive_action_usage WHERE wardrobe_id = :wardrobe AND action = :action")
                .param("wardrobe", wardrobe)
                .param("action", action.name())
                .query(Integer.class)
                .single();
    }

    private JsonNode upload(String subject, UUID key, String mime, long size, int expected)
            throws Exception {
        var action =
                mvc.perform(
                                as(post("/api/v1/garments/uploads"), subject)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(uploadBody(mime, size)))
                        .andExpect(status().is(expected));
        if (expected == 429) action.andExpect(header().exists("Retry-After"));
        return json.readTree(action.andReturn().getResponse().getContentAsString());
    }

    private String uploadBody(String mime, long size) {
        return json.writeValueAsString(
                Map.of(
                        "filename",
                        "piece.png",
                        "mimeType",
                        mime,
                        "size",
                        size,
                        "checksumSha256",
                        Base64.getEncoder().encodeToString(new byte[32]),
                        "imageRole",
                        "FRONT"));
    }

    private void search(String query, int expected) throws Exception {
        var result =
                mvc.perform(
                                as(
                                        get("/api/v1/search")
                                                .param("q", query)
                                                .param("mode", "SEMANTIC"),
                                        owner))
                        .andExpect(status().is(expected));
        if (expected == 429) result.andExpect(header().exists("Retry-After"));
    }

    private MockHttpServletRequestBuilder as(
            MockHttpServletRequestBuilder request, String subject) {
        return request.with(jwt().jwt(token -> token.subject(subject)));
    }
}
