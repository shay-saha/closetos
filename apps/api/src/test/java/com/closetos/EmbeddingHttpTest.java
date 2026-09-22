package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.platform.api.OutboxEntry;
import com.closetos.platform.api.OutboxQueue;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.EmbeddingProviderPort.VectorResult;
import com.closetos.search.application.EmbeddingGeneration;
import com.closetos.search.application.EmbeddingInputLoader;
import com.closetos.search.application.EmbeddingWorkRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class EmbeddingHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired EmbeddingGeneration generation;
    @Autowired EmbeddingInputLoader inputs;
    @Autowired EmbeddingWorkRegistry registry;
    @Autowired OutboxQueue queue;
    @MockitoBean EmbeddingProviderPort provider;
    final ModelInfo model =
            new ModelInfo(
                    "a".repeat(64),
                    new EmbeddingModel(
                            "bedrock", "amazon.titan-embed-image-v1", "1", "multimodal-1", 256));

    @BeforeEach
    void embeddings() {
        when(provider.model()).thenReturn(model);
        when(provider.embed(any(), any())).thenReturn(vector(model));
    }

    @Test
    void readyCreationQueuesWorkAndRepeatedDemandsReuseAVersionedVector() throws Exception {
        String owner = "embedding-ready";
        var garment = create(owner, "Green cotton shirt");
        UUID id = id(garment), wardrobe = wardrobe(id);
        assertThat(read(owner, endpoint(id)).path("state").asText()).isEqualTo("QUEUED");
        verify(provider, never()).model();
        var material = inputs.load(id, wardrobe).orElseThrow();
        assertThat(material.input().text()).contains("Green cotton shirt", "top");
        generation.generate(claim(id, 1));
        assertThat(read(owner, endpoint(id)).path("state").asText()).isEqualTo("READY");
        assertThat(
                        jdbc.sql(
                                        "SELECT vector_dims(embedding) FROM garment_embedding WHERE garment_id = :id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isEqualTo(256);
        assertThat(
                        jdbc.sql(
                                        "SELECT vector_norm(embedding) FROM garment_embedding WHERE garment_id = :id")
                                .param("id", id)
                                .query(Double.class)
                                .single())
                .isEqualTo(1.0);
        assertThat(
                        jdbc.sql(
                                        "SELECT source_fingerprint FROM garment_embedding WHERE garment_id = :id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo(material.fingerprint());
        send(
                owner,
                patch("/api/v1/garments/" + id),
                json.createObjectNode()
                        .put("version", 0)
                        .put("purchasePrice", 80)
                        .put("purchaseCurrency", "GBP"));
        generation.generate(claim(id, 1));
        verify(provider, times(1)).embed(any(), any());
        assertThat(read(owner, "/api/v1/garments/" + id).path("purchasePrice").asInt())
                .isEqualTo(80);
        assertThat(read(owner, endpoint(id)).path("state").asText()).isEqualTo("READY");
    }

    @Test
    void inferenceRunsOutsideTheGarmentTransactionAndStaleResultsAreRescheduled() throws Exception {
        String owner = "embedding-stale";
        var garment = create(owner, "Original green shirt");
        UUID id = id(garment);
        when(provider.embed(any(), any()))
                .thenAnswer(
                        invocation -> {
                            send(
                                    owner,
                                    patch("/api/v1/garments/" + id),
                                    json.createObjectNode()
                                            .put("version", 0)
                                            .put("name", "Corrected cream shirt"));
                            return vector(model);
                        })
                .thenReturn(vector(model));
        generation.generate(claim(id, 1));
        assertThat(count(id)).isZero();
        assertThat(read(owner, endpoint(id)).path("state").asText()).isEqualTo("QUEUED");
        generation.generate(claim(id, 1));
        assertThat(count(id)).isEqualTo(1);
        var material = inputs.load(id, wardrobe(id)).orElseThrow();
        assertThat(material.input().text()).contains("Corrected cream shirt");
        assertThat(
                        jdbc.sql(
                                        "SELECT source_fingerprint FROM garment_embedding WHERE garment_id = :id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo(material.fingerprint());
        assertThat(read(owner, endpoint(id)).path("state").asText()).isEqualTo("READY");
    }

    @Test
    void failedEmbeddingsKeepCanonicalGarmentsAndWearHistoryAvailable() throws Exception {
        String owner = "embedding-failure";
        UUID id = id(create(owner, "Reliable shirt"));
        when(provider.embed(any(), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        generation.generate(claim(id, 5));
        assertThat(count(id)).isZero();
        assertThat(read(owner, endpoint(id)).path("state").asText()).isEqualTo("FAILED");
        assertThat(read(owner, endpoint(id)).path("failureCode").asText())
                .isEqualTo("EMBEDDING_FAILURE");
        var current = read(owner, "/api/v1/garments/" + id);
        assertThat(current.path("processingStatus").asText()).isEqualTo("READY");
        assertThat(current.path("name").asText()).isEqualTo("Reliable shirt");
        var wear = json.createObjectNode().put("wornOn", java.time.LocalDate.now().toString());
        wear.set("garmentIds", json.createArrayNode().add(id.toString()));
        mvc.perform(
                        as(post("/api/v1/wear-events"), owner)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wear.toString()))
                .andExpect(status().isCreated());
        assertThat(read(owner, "/api/v1/garments/" + id).path("wearCount").asInt()).isEqualTo(1);
    }

    @Test
    void workLeasesRecoverAfterExpiryAndOldOwnersCannotOverwriteTheReplacement() throws Exception {
        UUID id = id(create("embedding-leases", "Leased shirt"));
        var material = inputs.load(id, wardrobe(id)).orElseThrow();
        var first = claim(id, 1);
        assertThat(registry.begin(first, material, model)).isTrue();
        var next = new OutboxEntry(UUID.randomUUID(), id, first.eventType(), first.payload(), 2);
        assertThatThrownBy(() -> registry.begin(next, material, model))
                .isInstanceOf(EmbeddingWorkRegistry.Busy.class);
        jdbc.sql(
                        "UPDATE garment_embedding_work SET lease_until = now() - INTERVAL '1 second' WHERE garment_id = :id")
                .param("id", id)
                .update();
        assertThat(registry.begin(next, material, model)).isTrue();
        assertThat(registry.complete(first, material, model, vector(model))).isFalse();
        assertThat(count(id)).isZero();
        assertThat(registry.complete(next, material, model, vector(model))).isTrue();
        assertThat(count(id)).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE garment_embedding SET dimensions = 384 WHERE garment_id = :id")
                                        .param("id", id)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ownershipAndDeletionAreRecheckedBeforePersistingBackgroundResults() throws Exception {
        String owner = "embedding-owner";
        UUID id = id(create(owner, "Private shirt"));
        UUID foreign = id(create("embedding-stranger", "Other shirt"));
        assertThat(inputs.load(id, wardrobe(foreign))).isEmpty();
        mvc.perform(as(get(endpoint(id)), "embedding-stranger")).andExpect(status().isNotFound());
        mvc.perform(get(endpoint(id))).andExpect(status().isUnauthorized());
        when(provider.embed(any(), any()))
                .thenAnswer(
                        invocation -> {
                            mvc.perform(
                                            as(
                                                    delete("/api/v1/garments/" + id)
                                                            .param("version", "0"),
                                                    owner))
                                    .andExpect(status().isNoContent());
                            return vector(model);
                        });
        generation.generate(claim(id, 1));
        assertThat(count(id)).isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM garment_embedding_work WHERE garment_id = :id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isZero();
        mvc.perform(as(get(endpoint(id)), owner)).andExpect(status().isNotFound());
    }

    @Test
    void eventConsumersClaimOnlyTheirOwnTypesAndDeferredClaimsPreserveRetryBudget()
            throws Exception {
        UUID id = id(create("embedding-queue", "Queued shirt"));
        var embedding = queue.claim(Set.of("GENERATE_EMBEDDING"));
        assertThat(embedding)
                .hasSize(1)
                .allMatch(event -> event.eventType().equals("GENERATE_EMBEDDING"));
        assertThat(queue.claim(Set.of("START_PROCESSING", "DELETE_MEDIA", "DELETE_ORIGINAL")))
                .isEmpty();
        var event = embedding.getFirst();
        queue.defer(event, java.time.Duration.ZERO);
        assertThat(
                        jdbc.sql("SELECT publish_attempts FROM outbox_event WHERE id = :id")
                                .param("id", event.id())
                                .query(Integer.class)
                                .single())
                .isEqualTo(event.publishAttempts() - 1);
        assertThat(count(id)).isZero();
        assertThatThrownBy(() -> new VectorResult(model.modelKey(), model.model(), List.of(1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        var invalid = new ArrayList<>(vector(model).vector());
        invalid.set(0, Double.NaN);
        assertThatThrownBy(() -> new VectorResult(model.modelKey(), model.model(), invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxEntry claim(UUID id, int attempt) {
        return jdbc.sql(
                        """
                UPDATE outbox_event SET publish_attempts = :attempt, lease_until = now() + INTERVAL '6 minutes'
                WHERE id = (SELECT id FROM outbox_event WHERE aggregate_id = :id AND event_type = 'GENERATE_EMBEDDING'
                    AND published_at IS NULL ORDER BY created_at LIMIT 1)
                RETURNING id, aggregate_id, event_type, payload::text, publish_attempts
                """)
                .param("id", id)
                .param("attempt", attempt)
                .query(OutboxEntry.class)
                .single();
    }

    private int count(UUID id) {
        return jdbc.sql("SELECT count(*) FROM garment_embedding WHERE garment_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private UUID wardrobe(UUID garment) {
        return jdbc.sql("SELECT wardrobe_id FROM garment WHERE id = :id")
                .param("id", garment)
                .query(UUID.class)
                .single();
    }

    private VectorResult vector(ModelInfo info) {
        var values = new ArrayList<Double>();
        for (int i = 0; i < info.model().dimensions(); i++) values.add(i == 0 ? 1.0 : 0.0);
        return new VectorResult(info.modelKey(), info.model(), values);
    }

    private JsonNode create(String owner, String name) throws Exception {
        var body = json.createObjectNode().put("name", name).put("category", "TOP");
        var result =
                mvc.perform(
                                as(post("/api/v1/garments"), owner)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body.toString()))
                        .andExpect(status().isCreated());
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private UUID id(JsonNode garment) {
        return UUID.fromString(garment.path("id").asText());
    }

    private String endpoint(UUID id) {
        return "/api/v1/garments/" + id + "/embedding";
    }

    private JsonNode read(String owner, String endpoint) throws Exception {
        return json.readTree(
                mvc.perform(as(get(endpoint), owner))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private void send(String owner, MockHttpServletRequestBuilder request, ObjectNode body)
            throws Exception {
        mvc.perform(
                        as(request, owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.toString()))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String owner) {
        return request.with(jwt().jwt(token -> token.subject(owner)));
    }
}
