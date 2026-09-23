package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.platform.api.OutboxQueue;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.EmbeddingProviderPort.VectorResult;
import com.closetos.search.application.EmbeddingGeneration;
import com.closetos.search.application.EmbeddingInputLoader;
import com.closetos.search.application.EmbeddingWorkRegistry;
import com.closetos.search.application.ReembeddingJobTracker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class ReembeddingHttpTest extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/admin/search/embedding-jobs";
    private static final ModelInfo MODEL =
            new ModelInfo(
                    "d".repeat(64),
                    new EmbeddingModel(
                            "bedrock", "amazon.titan-embed-image-v1", "1", "multimodal-1", 256));
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired EmbeddingGeneration generation;
    @Autowired EmbeddingInputLoader inputs;
    @Autowired EmbeddingWorkRegistry registry;
    @Autowired ReembeddingJobTracker tracker;
    @Autowired OutboxQueue queue;
    @MockitoBean EmbeddingProviderPort provider;

    @BeforeEach
    void model() {
        when(provider.model()).thenReturn(MODEL);
        when(provider.embed(any(), any()))
                .thenAnswer(invocation -> vector(invocation.getArgument(1)));
    }

    @Test
    void administratorNamespaceRejectsUnauthenticatedAndOrdinaryUsersBeforeProviderCalls()
            throws Exception {
        for (var request :
                List.of(
                        get(PATH),
                        post(PATH)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        get(PATH + "/" + UUID.randomUUID())))
            mvc.perform(request).andExpect(status().isUnauthorized());
        for (var request :
                List.of(
                        get(PATH),
                        post(PATH)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        get(PATH + "/" + UUID.randomUUID())))
            mvc.perform(
                            request.with(
                                    jwt().jwt(
                                                    token ->
                                                            token.subject("ordinary-user")
                                                                    .claim(
                                                                            "roles",
                                                                            List.of(
                                                                                    "closetos-admin")))))
                    .andExpect(status().isForbidden());
        verify(provider, never()).model();
    }

    @Test
    void configuredGroupConverterRunsOnAuthenticatedBearerRequests() throws Exception {
        when(jwtDecoder.decode("administrator-access"))
                .thenReturn(
                        Jwt.withTokenValue("administrator-access")
                                .header("alg", "RS256")
                                .subject("bearer-administrator")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(300))
                                .claim("cognito:groups", List.of("closetos-admin"))
                                .build());
        mvc.perform(get(PATH).header("Authorization", "Bearer administrator-access"))
                .andExpect(status().isOk());
        when(jwtDecoder.decode("ordinary-access"))
                .thenReturn(
                        Jwt.withTokenValue("ordinary-access")
                                .header("alg", "RS256")
                                .subject("bearer-user")
                                .claim("roles", List.of("closetos-admin"))
                                .build());
        mvc.perform(get(PATH).header("Authorization", "Bearer ordinary-access"))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopedSnapshotIsDurableAndIdempotentAndIgnoresUnreviewedAndForeignPieces()
            throws Exception {
        String owner = "rebuild-scoped";
        var first = create(owner, "Green shirt");
        var second = create(owner, "Blue jeans");
        var unreviewed = create(owner, "Unreviewed piece");
        create("rebuild-foreign", "Foreign coat");
        jdbc.sql("UPDATE garment SET processing_status = 'READY_FOR_REVIEW' WHERE id = :id")
                .param("id", id(unreviewed))
                .update();
        UUID key = UUID.randomUUID();
        var job = start(owner, "{}", key);
        assertThat(job.path("total").asInt()).isEqualTo(2);
        assertThat(job.path("state").asText()).isEqualTo("QUEUED");
        assertThat(job.path("completedAt").isNull()).isTrue();
        verify(provider, never()).embed(any(), any());
        assertThat(start(owner, "{}", key).path("id").asText()).isEqualTo(job.path("id").asText());
        assertThat(events(id(job))).containsExactlyInAnyOrder(id(first), id(second));
        create(owner, "Added after snapshot");
        assertThat(read(owner, id(job)).path("total").asInt()).isEqualTo(2);
        when(provider.model())
                .thenThrow(
                        new DomainException(503, "EMBEDDING_UNAVAILABLE", "Provider unavailable"));
        assertThat(start(owner, "{}", key).path("id").asText()).isEqualTo(job.path("id").asText());
        mvc.perform(
                        admin(owner, post(PATH))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"allWardrobes\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void globalAndExplicitWardrobeScopesCaptureTheApprovedCorpusAndValidateInputs()
            throws Exception {
        var owned = create("rebuild-global-a", "Own coat");
        var foreign = create("rebuild-global-b", "Other shirt");
        UUID foreignWardrobe = wardrobe(id(foreign));
        var scoped =
                start(
                        "rebuild-global-a",
                        "{\"wardrobeId\":\"" + foreignWardrobe + "\"}",
                        UUID.randomUUID());
        assertThat(events(id(scoped))).containsExactly(id(foreign));
        int ready =
                jdbc.sql("SELECT count(*) FROM garment WHERE processing_status = 'READY'")
                        .query(Integer.class)
                        .single();
        var all = start("rebuild-global-a", "{\"allWardrobes\":true}", UUID.randomUUID());
        assertThat(all.path("total").asInt()).isEqualTo(ready);
        assertThat(all.path("wardrobeId").isNull()).isTrue();
        assertThat(events(id(all))).contains(id(owned), id(foreign));
        for (String body :
                List.of(
                        "{\"allWardrobes\":true,\"wardrobeId\":\"" + foreignWardrobe + "\"}",
                        "{\"wardrobeId\":\"not-a-uuid\"}"))
            mvc.perform(
                            admin("rebuild-global-a", post(PATH))
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        admin("rebuild-global-a", post(PATH))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"wardrobeId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        admin("rebuild-global-a", post(PATH))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin("rebuild-global-a", get(PATH + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void forcedRebuildKeepsValidSearchAvailableAndTracksARecoveredRetry() throws Exception {
        String owner = "rebuild-valid-search";
        var piece = create(owner, "Olive shirt");
        generation.generate(claimNormal(id(piece)));
        var job = start(owner, "{}", UUID.randomUUID());
        var event = claim(id(job), id(piece));
        doAnswer(
                        invocation -> {
                            mvc.perform(
                                            get("/api/v1/garments/" + id(piece) + "/similar")
                                                    .with(jwt().jwt(token -> token.subject(owner))))
                                    .andExpect(status().isOk());
                            throw new DomainException(
                                    503, "UNAVAILABLE", "Provider secret must not be exposed");
                        })
                .when(provider)
                .embed(any(), any());
        generation.generate(event);
        assertThat(read(owner, id(job)).path("state").asText()).isEqualTo("RUNNING");
        assertThat(read(owner, id(job)).path("failed").asInt()).isZero();
        mvc.perform(
                        get("/api/v1/garments/" + id(piece) + "/similar")
                                .with(jwt().jwt(token -> token.subject(owner))))
                .andExpect(status().isOk());
        doReturn(vector(MODEL)).when(provider).embed(any(), any());
        makeAvailable(event.id());
        generation.generate(claim(id(job), id(piece)));
        var completed = read(owner, id(job));
        assertThat(completed.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("succeeded").asInt()).isEqualTo(1);
        assertThat(completed.path("completedAt").isNull()).isFalse();
        assertThat(completed.path("failureCounts").size()).isZero();
        assertThat(
                        jdbc.sql("SELECT failure_detail IS NULL FROM outbox_event WHERE id = :id")
                                .param("id", event.id())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    void completedVectorSurvivesALostAcknowledgementWithoutAnotherPaidInference() throws Exception {
        String owner = "rebuild-acknowledgement";
        var piece = create(owner, "Shirt");
        var job = start(owner, "{}", UUID.randomUUID());
        var event = claim(id(job), id(piece));
        var material = inputs.load(id(piece), wardrobe(id(piece))).orElseThrow();
        assertThat(registry.begin(event, material, MODEL)).isTrue();
        assertThat(
                        registry.complete(
                                event, material, MODEL, provider.embed(material.input(), MODEL)))
                .isTrue();
        assertThat(read(owner, id(job)).path("succeeded").asInt()).isZero();
        makeAvailable(event.id());
        generation.generate(claim(id(job), id(piece)));
        assertThat(read(owner, id(job)).path("state").asText()).isEqualTo("SUCCEEDED");
        verify(provider, times(1)).embed(any(), any());
    }

    @Test
    void completedCheckpointSurvivesAnotherModelMigrationBeforeAcknowledgement() throws Exception {
        String owner = "rebuild-migration-checkpoint";
        var piece = create(owner, "Green shirt");
        var original = start(owner, "{}", UUID.randomUUID());
        var event = claim(id(original), id(piece));
        var material = inputs.load(id(piece), wardrobe(id(piece))).orElseThrow();
        assertThat(registry.begin(event, material, MODEL)).isTrue();
        assertThat(
                        registry.complete(
                                event, material, MODEL, provider.embed(material.input(), MODEL)))
                .isTrue();
        tracker.outcome(event, null, "EMBEDDING_FAILURE");
        tracker.outcome(event, "FAILED", "EMBEDDING_FAILURE");
        assertThat(tracker.checkpoint(event))
                .contains(new ReembeddingJobTracker.Checkpoint("SUCCEEDED", null));
        var changed =
                new ModelInfo(
                        "f".repeat(64),
                        new EmbeddingModel(
                                "bedrock",
                                "amazon.titan-embed-image-v1",
                                "3",
                                "multimodal-1",
                                384));
        when(provider.model()).thenReturn(changed);
        var migrated = start(owner, "{}", UUID.randomUUID());
        generation.generate(claim(id(migrated), id(piece)));
        makeAvailable(event.id());
        clearInvocations(provider);
        generation.generate(claim(id(original), id(piece)));
        assertThat(read(owner, id(original)).path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(read(owner, id(migrated)).path("state").asText()).isEqualTo("SUCCEEDED");
        verify(provider, never()).model();
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void expiredLeasesResumeAndDeletedTargetsAreSkippedWithoutResurrection() throws Exception {
        String owner = "rebuild-resume";
        var deleted = create(owner, "Deleted shirt");
        var active = create(owner, "Active trousers");
        var job = start(owner, "{}", UUID.randomUUID());
        var event = claim(id(job), id(active));
        var material = inputs.load(id(active), wardrobe(id(active))).orElseThrow();
        assertThat(registry.begin(event, material, MODEL)).isTrue();
        jdbc.sql(
                        "UPDATE garment_embedding_work SET lease_until = now() - interval '1 second' WHERE garment_id = :id")
                .param("id", id(active))
                .update();
        makeAvailable(event.id());
        generation.generate(claim(id(job), id(active)));
        mvc.perform(
                        delete("/api/v1/garments/" + id(deleted) + "?version=0")
                                .with(jwt().jwt(token -> token.subject(owner))))
                .andExpect(status().isNoContent());
        generation.generate(claim(id(job), id(deleted)));
        var done = read(owner, id(job));
        assertThat(done.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("succeeded").asInt()).isEqualTo(1);
        assertThat(done.path("skipped").asInt()).isEqualTo(1);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM garment_embedding WHERE garment_id = :id")
                                .param("id", id(deleted))
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void modelChangesFailExplicitlyAndANewJobKeepsVectorNamespacesSeparate() throws Exception {
        String owner = "rebuild-model-change";
        var piece = create(owner, "Blue shirt");
        generation.generate(claimNormal(id(piece)));
        var old = start(owner, "{}", UUID.randomUUID());
        var changed =
                new ModelInfo(
                        "e".repeat(64),
                        new EmbeddingModel(
                                "bedrock",
                                "amazon.titan-embed-image-v1",
                                "2",
                                "multimodal-1",
                                384));
        when(provider.model()).thenReturn(changed);
        clearInvocations(provider);
        generation.generate(claim(id(old), id(piece)));
        assertThat(
                        read(owner, id(old))
                                .path("failureCounts")
                                .path("EMBEDDING_MODEL_CHANGED")
                                .asInt())
                .isEqualTo(1);
        verify(provider, never()).embed(any(), any());
        var current = start(owner, "{}", UUID.randomUUID());
        generation.generate(claim(id(current), id(piece)));
        assertThat(read(owner, id(current)).path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.sql(
                                        "SELECT dimensions FROM garment_embedding WHERE garment_id = :id ORDER BY dimensions")
                                .param("id", id(piece))
                                .query(Integer.class)
                                .list())
                .containsExactly(256, 384);
    }

    @Test
    void boundedFailuresAreDurableAndExposeOnlyPublicFailureCodes() throws Exception {
        String owner = "rebuild-failure";
        var piece = create(owner, "Black coat");
        var job = start(owner, "{}", UUID.randomUUID());
        doThrow(new DomainException(503, "UNAVAILABLE", "private account diagnostic"))
                .when(provider)
                .embed(any(), any());
        for (int attempt = 0; attempt < 5; attempt++) {
            var event = claim(id(job), id(piece));
            generation.generate(event);
            makeAvailable(event.id());
        }
        var failed = read(owner, id(job));
        assertThat(failed.path("state").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failed").asInt()).isEqualTo(1);
        assertThat(failed.path("failureCounts").path("EMBEDDING_FAILURE").asInt()).isEqualTo(1);
        assertThat(failed.toString())
                .doesNotContain("private account", "modelKey", "embedding", "source_fingerprint");
        verify(provider, times(5)).embed(any(), any());
    }

    @Test
    void concurrentRetriesCreateOneJobAndDoNotCallTheProviderInsideATransaction() throws Exception {
        String owner = "rebuild-concurrent";
        create(owner, "One snapshot piece");
        UUID key = UUID.randomUUID();
        when(provider.model())
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return MODEL;
                        });
        var requests =
                java.util.stream.IntStream.range(0, 2)
                        .mapToObj(
                                index ->
                                        java.util.concurrent.CompletableFuture.supplyAsync(
                                                () -> {
                                                    try {
                                                        return start(owner, "{}", key);
                                                    } catch (Exception exception) {
                                                        throw new java.util.concurrent
                                                                .CompletionException(exception);
                                                    }
                                                }))
                        .toList();
        var first = requests.getFirst().get(10, java.util.concurrent.TimeUnit.SECONDS);
        var second = requests.getLast().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(first.path("id").asText()).isEqualTo(second.path("id").asText());
        assertThat(events(id(first))).hasSize(1);
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void emptyScopesCompleteImmediatelyAndUnavailableProvidersDoNotQueueAJob() throws Exception {
        var empty = start("rebuild-empty", "{}", UUID.randomUUID());
        assertThat(empty.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(empty.path("total").asInt()).isZero();
        assertThat(empty.path("completedAt").isNull()).isFalse();
        when(provider.model())
                .thenThrow(
                        new DomainException(
                                503,
                                "EMBEDDING_UNAVAILABLE",
                                "Embedding provider is not configured."));
        mvc.perform(
                        admin("rebuild-unavailable", post(PATH))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM reembedding_job WHERE requested_by = 'rebuild-unavailable'")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private JsonNode start(String owner, String body, UUID key) throws Exception {
        return json.readTree(
                mvc.perform(
                                admin(owner, post(PATH))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private JsonNode read(String owner, UUID job) throws Exception {
        return json.readTree(
                mvc.perform(admin(owner, get(PATH + "/" + job)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private MockHttpServletRequestBuilder admin(
            String owner, MockHttpServletRequestBuilder request) {
        return request.with(
                jwt().jwt(token -> token.subject(owner))
                        .authorities(new SimpleGrantedAuthority("ROLE_CLOSETOS_ADMIN")));
    }

    private JsonNode create(String owner, String name) throws Exception {
        return json.readTree(
                mvc.perform(
                                post("/api/v1/garments")
                                        .with(jwt().jwt(token -> token.subject(owner)))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        java.util.Map.of(
                                                                "name", name, "category", "TOP"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private UUID id(JsonNode object) {
        return UUID.fromString(object.path("id").asText());
    }

    private UUID wardrobe(UUID garment) {
        return jdbc.sql("SELECT wardrobe_id FROM garment WHERE id = :id")
                .param("id", garment)
                .query(UUID.class)
                .single();
    }

    private List<UUID> events(UUID job) {
        return jdbc.sql(
                        "SELECT o.aggregate_id FROM reembedding_item i JOIN outbox_event o ON o.id = i.event_id WHERE i.job_id = :job")
                .param("job", job)
                .query(UUID.class)
                .list();
    }

    private OutboxEntry claim(UUID job, UUID garment) {
        return jdbc.sql(
                        """
                UPDATE outbox_event SET publish_attempts = publish_attempts + 1, lease_until = now() + interval '6 minutes'
                WHERE id = (SELECT o.id FROM reembedding_item i JOIN outbox_event o ON o.id = i.event_id WHERE i.job_id = :job AND o.aggregate_id = :garment AND o.published_at IS NULL)
                RETURNING id, aggregate_id, event_type, payload::text, publish_attempts
                """)
                .param("job", job)
                .param("garment", garment)
                .query(OutboxEntry.class)
                .single();
    }

    private OutboxEntry claimNormal(UUID garment) {
        return jdbc.sql(
                        """
                UPDATE outbox_event SET publish_attempts = publish_attempts + 1, lease_until = now() + interval '6 minutes'
                WHERE event_type = 'GENERATE_EMBEDDING' AND aggregate_id = :garment AND published_at IS NULL
                RETURNING id, aggregate_id, event_type, payload::text, publish_attempts
                """)
                .param("garment", garment)
                .query(OutboxEntry.class)
                .single();
    }

    private void makeAvailable(UUID event) {
        jdbc.sql("UPDATE outbox_event SET lease_until = NULL, available_at = now() WHERE id = :id")
                .param("id", event)
                .update();
    }

    private VectorResult vector(ModelInfo model) {
        var values =
                new ArrayList<>(java.util.Collections.nCopies(model.model().dimensions(), 0.0));
        values.set(0, 1.0);
        return new VectorResult(model.modelKey(), model.model(), values);
    }
}
