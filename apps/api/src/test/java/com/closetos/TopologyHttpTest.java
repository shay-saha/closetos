package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.EmbeddingProviderPort.VectorResult;
import com.closetos.search.application.EmbeddingGeneration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class TopologyHttpTest extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/insights/topology";
    private static final ModelInfo MODEL =
            new ModelInfo(
                    "9".repeat(64),
                    new EmbeddingModel(
                            "bedrock", "amazon.titan-embed-image-v1", "1", "multimodal-1", 256));
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired EmbeddingGeneration generation;
    @MockitoBean EmbeddingProviderPort provider;

    @BeforeEach
    void model() {
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return MODEL;
                        })
                .when(provider)
                .model();
    }

    @Test
    void authenticationAndBoundsAreCheckedBeforeReadingTheProvider() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (var request :
                List.of(
                        get(PATH).param("neighbours", "1"),
                        get(PATH).param("neighbours", "6"),
                        get(PATH).param("limit", "0"),
                        get(PATH).param("limit", "2001"),
                        get(PATH).param("category", "' OR 1=1 --")))
            mvc.perform(as(request, "graph-validation")).andExpect(status().isBadRequest());
        verify(provider, never()).model();
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void nearestNeighboursAreOwnerScopedDeduplicatedAndFilteredBeforeRanking() throws Exception {
        String owner = "graph-neighbours";
        UUID a = piece(owner, "First shirt", "TOP", 0);
        UUID b = piece(owner, "Second shirt", "TOP", .1);
        UUID c = piece(owner, "Third shirt", "TOP", .2);
        UUID d = piece(owner, "First shoe", "SHOES", 1.4);
        UUID e = piece(owner, "Second shoe", "SHOES", 1.5);
        UUID f = piece(owner, "Third shoe", "SHOES", 1.6);
        UUID foreign = piece("graph-foreign", "Private shirt", "TOP", 0);
        UUID pending = draft(owner, "Waiting for an embedding", "TOP");
        UUID review = draft(owner, "Unreviewed photo", "TOP");
        jdbc.sql("UPDATE garment SET processing_status = 'READY_FOR_REVIEW' WHERE id = :id")
                .param("id", review)
                .update();
        UUID archived = piece(owner, "Archived shirt", "TOP", 0);
        jdbc.sql("UPDATE garment SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", archived)
                .update();
        clearInvocations(provider);

        var graph =
                read(
                        owner,
                        get(PATH)
                                .param("neighbours", "2")
                                .param("wardrobeId", wardrobe(foreign).toString()));
        assertThat(ids(graph))
                .containsExactlyInAnyOrder(
                        a.toString(),
                        b.toString(),
                        c.toString(),
                        d.toString(),
                        e.toString(),
                        f.toString(),
                        pending.toString());
        assertThat(graph.path("eligibleCount").asInt()).isEqualTo(7);
        assertThat(graph.path("indexedCount").asInt()).isEqualTo(6);
        assertThat(graph.path("truncated").asBoolean()).isFalse();
        assertThat(edges(graph))
                .containsExactlyInAnyOrder(
                        pair(a, b), pair(a, c), pair(b, c), pair(d, e), pair(d, f), pair(e, f));
        assertThat(graph.path("edges").size()).isEqualTo(6);
        for (var edge : graph.path("edges")) {
            assertThat(edge.path("source").asText()).isNotEqualTo(edge.path("target").asText());
            assertThat(edge.path("weight").asDouble()).isBetween(0.0, 1.0);
            if (pair(edge).equals(pair(a, b)))
                assertThat(edge.path("weight").asDouble()).isCloseTo(Math.cos(.1), within(1e-6));
        }
        assertThat(node(graph, pending).path("indexed").asBoolean()).isFalse();
        var tops = read(owner, get(PATH).param("category", "TOP").param("neighbours", "2"));
        assertThat(ids(tops))
                .containsExactlyInAnyOrder(
                        a.toString(), b.toString(), c.toString(), pending.toString());
        assertThat(edges(tops)).containsExactlyInAnyOrder(pair(a, b), pair(a, c), pair(b, c));
        assertThat(tops.path("indexedCount").asInt()).isEqualTo(3);
        var shoes = read(owner, get(PATH).param("category", "SHOES").param("neighbours", "5"));
        assertThat(ids(shoes)).containsExactlyInAnyOrder(d.toString(), e.toString(), f.toString());
        assertThat(edges(shoes)).containsExactlyInAnyOrder(pair(d, e), pair(d, f), pair(e, f));
        assertThat(graph.toString())
                .doesNotContain("Private shirt", "modelKey", "sourceFingerprint", "embedding\":");
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void editedSourcesStayVisibleButDoNotProduceStaleRelationships() throws Exception {
        String owner = "graph-stale";
        UUID a = piece(owner, "Cotton shirt", "TOP", 0);
        UUID b = piece(owner, "Linen shirt", "TOP", .1);
        assertThat(read(owner, get(PATH)).path("edges").size()).isEqualTo(1);
        mvc.perform(
                        as(patch("/api/v1/garments/" + a), owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"name\":\"Changed shirt\"}"))
                .andExpect(status().isOk());
        var graph = read(owner, get(PATH));
        assertThat(ids(graph)).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(node(graph, a).path("name").asText()).isEqualTo("Changed shirt");
        assertThat(node(graph, a).path("indexed").asBoolean()).isFalse();
        assertThat(graph.path("indexedCount").asInt()).isEqualTo(1);
        assertThat(graph.path("edges").isEmpty()).isTrue();
        generation.generate(claim(a));
        assertThat(read(owner, get(PATH)).path("edges").size()).isEqualTo(1);
    }

    @Test
    void modelMigrationNeverConnectsDifferentVectorSpaces() throws Exception {
        String owner = "graph-model-migration";
        UUID a = piece(owner, "First dress", "DRESS", 0);
        UUID b = piece(owner, "Second dress", "DRESS", .1);
        UUID c = piece(owner, "Third dress", "DRESS", .2);
        var changed =
                new ModelInfo(
                        "8".repeat(64),
                        new EmbeddingModel(
                                "bedrock",
                                "amazon.titan-embed-image-v1",
                                "2",
                                "multimodal-1",
                                384));
        doReturn(changed).when(provider).model();
        var pending = read(owner, get(PATH));
        assertThat(pending.path("indexedCount").asInt()).isZero();
        assertThat(pending.path("edges").isEmpty()).isTrue();
        for (UUID id : List.of(a, b)) {
            doReturn(vector(changed, 0)).when(provider).embed(any(), any());
            requeue(id);
            generation.generate(claim(id));
        }
        var graph = read(owner, get(PATH));
        assertThat(graph.path("model").path("dimensions").asInt()).isEqualTo(384);
        assertThat(graph.path("indexedCount").asInt()).isEqualTo(2);
        assertThat(node(graph, c).path("indexed").asBoolean()).isFalse();
        assertThat(edges(graph)).containsExactly(pair(a, b));
        assertThat(
                        jdbc.sql("SELECT count(*) FROM garment_embedding WHERE garment_id = :id")
                                .param("id", a)
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void unavailableEmbeddingsRetainHonestGarmentMetricsAndAnAccessibleDataSet() throws Exception {
        String owner = "graph-unavailable";
        UUID a = piece(owner, "Paid shirt", "TOP", 0);
        UUID b = piece(owner, "Unknown price shirt", "TOP", .1);
        jdbc.sql(
                        """
                UPDATE garment SET primary_colour_hex = '#246B45', purchase_price = 75,
                    purchase_currency = 'GBP', wear_count_cached = 3, last_worn_at = '2026-09-01'
                WHERE id = :id
                """)
                .param("id", a)
                .update();
        clearInvocations(provider);
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            throw new DomainException(
                                    503, "EMBEDDINGS_UNAVAILABLE", "Provider unavailable.");
                        })
                .when(provider)
                .model();
        var graph = read(owner, get(PATH));
        assertThat(ids(graph)).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(graph.path("embeddingAvailability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(graph.path("model").isNull()).isTrue();
        assertThat(graph.path("indexedCount").asInt()).isZero();
        assertThat(graph.path("edges").isEmpty()).isTrue();
        assertThat(node(graph, a).path("colour").asText()).isEqualTo("#246B45");
        assertThat(node(graph, a).path("wearCount").asInt()).isEqualTo(3);
        assertThat(node(graph, a).path("costPerWear").decimalValue()).isEqualByComparingTo("25");
        assertThat(node(graph, a).path("purchaseCurrency").asText()).isEqualTo("GBP");
        assertThat(node(graph, a).path("lastWornAt").asText()).isEqualTo("2026-09-01");
        assertThat(node(graph, b).path("costPerWear").isNull()).isTrue();
        assertThat(node(graph, b).path("lastWornAt").isNull()).isTrue();
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void truncationIsExplicitStableAndRelationshipsOnlyReferenceShownNodes() throws Exception {
        String owner = "graph-truncated";
        for (int n = 0; n < 4; n++) piece(owner, "Graph piece " + n, "TOP", n * .1);
        var first = read(owner, get(PATH).param("limit", "2"));
        var repeated = read(owner, get(PATH).param("limit", "2"));
        assertThat(ids(first)).containsExactlyElementsOf(ids(repeated));
        assertThat(first.path("eligibleCount").asInt()).isEqualTo(4);
        assertThat(first.path("indexedCount").asInt()).isEqualTo(2);
        assertThat(first.path("truncated").asBoolean()).isTrue();
        assertThat(first.path("edges").size()).isEqualTo(1);
        for (var edge : first.path("edges")) {
            assertThat(ids(first))
                    .contains(edge.path("source").asText(), edge.path("target").asText());
        }
        var empty = read("graph-empty", get(PATH));
        assertThat(empty.path("nodes").isEmpty()).isTrue();
        assertThat(empty.path("edges").isEmpty()).isTrue();
        assertThat(empty.path("eligibleCount").asInt()).isZero();
        assertThat(empty.path("indexedCount").asInt()).isZero();
        assertThat(empty.path("truncated").asBoolean()).isFalse();
    }

    @Test
    void representativeThousandPieceWardrobeHasBoundedEdgesAndQueryTime() throws Exception {
        String owner = "graph-thousand";
        UUID seed = piece(owner, "Seed piece", "TOP", 0);
        UUID wardrobe = wardrobe(seed);
        jdbc.sql("UPDATE garment SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", seed)
                .update();
        jdbc.sql(
                        """
                INSERT INTO garment(id, wardrobe_id, name, category, created_at, updated_at)
                SELECT gen_random_uuid(), :wardrobe, 'Graph piece ' || n, 'TOP', now(), now()
                FROM generate_series(1, 1000) n
                """)
                .param("wardrobe", wardrobe)
                .update();
        jdbc.sql(
                        """
                INSERT INTO garment_embedding(garment_id, wardrobe_id, model_key, dimensions, embedding, source_fingerprint, updated_at)
                SELECT id, wardrobe_id, :model, 256,
                    CAST('[' || cos(n * 0.006283185307179586) || ',' || sin(n * 0.006283185307179586)
                        || repeat(',0', 254) || ']' AS vector), :fingerprint, now()
                FROM (SELECT *, row_number() OVER (ORDER BY id) AS n FROM garment
                    WHERE wardrobe_id = :wardrobe AND status <> 'ARCHIVED') pieces
                """)
                .param("wardrobe", wardrobe)
                .param("model", MODEL.modelKey())
                .param("fingerprint", "7".repeat(64))
                .update();
        jdbc.sql(
                        """
                INSERT INTO garment_embedding_work(garment_id, wardrobe_id, model_key, source_fingerprint, state, updated_at)
                SELECT garment_id, wardrobe_id, model_key, source_fingerprint, 'READY', now()
                FROM garment_embedding WHERE wardrobe_id = :wardrobe AND garment_id <> :seed
                """)
                .param("wardrobe", wardrobe)
                .param("seed", seed)
                .update();
        var timings = new ArrayList<Double>();
        for (int request = 0; request < 5; request++) {
            long started = System.nanoTime();
            var graph = read(owner, get(PATH));
            timings.add((System.nanoTime() - started) / 1_000_000.0);
            assertThat(graph.path("nodes").size()).isEqualTo(1000);
            assertThat(graph.path("indexedCount").asInt()).isEqualTo(1000);
            assertThat(graph.path("truncated").asBoolean()).isFalse();
            assertThat(graph.path("edges").size()).isBetween(1000, 3000);
            var connected = new HashSet<String>();
            for (var edge : graph.path("edges")) {
                connected.add(edge.path("source").asText());
                connected.add(edge.path("target").asText());
            }
            assertThat(connected).containsExactlyInAnyOrderElementsOf(ids(graph));
        }
        double slowest = Collections.max(timings);
        System.out.printf("Topology over 1000 vectors: slowest of five %.1f ms%n", slowest);
        assertThat(slowest).isLessThan(2000);
    }

    private UUID draft(String owner, String name, String category) throws Exception {
        return UUID.fromString(
                json.readTree(
                                mvc.perform(
                                                as(post("/api/v1/garments"), owner)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json.createObjectNode()
                                                                        .put("name", name)
                                                                        .put("category", category)
                                                                        .toString()))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("id")
                        .asText());
    }

    private UUID piece(String owner, String name, String category, double angle) throws Exception {
        UUID id = draft(owner, name, category);
        doReturn(vector(MODEL, angle)).when(provider).embed(any(), any());
        generation.generate(claim(id));
        return id;
    }

    private VectorResult vector(ModelInfo model, double angle) {
        var coordinates = new ArrayList<>(Collections.nCopies(model.model().dimensions(), 0.0));
        coordinates.set(0, Math.cos(angle));
        coordinates.set(1, Math.sin(angle));
        return new VectorResult(model.modelKey(), model.model(), coordinates);
    }

    private void requeue(UUID id) {
        jdbc.sql(
                        "UPDATE outbox_event SET published_at = NULL, lease_until = NULL WHERE aggregate_id = :id AND event_type = 'GENERATE_EMBEDDING'")
                .param("id", id)
                .update();
    }

    private OutboxEntry claim(UUID id) {
        return jdbc.sql(
                        """
                UPDATE outbox_event SET publish_attempts = publish_attempts + 1, lease_until = now() + interval '6 minutes'
                WHERE id = (SELECT id FROM outbox_event WHERE aggregate_id = :id AND event_type = 'GENERATE_EMBEDDING'
                    AND published_at IS NULL ORDER BY created_at LIMIT 1)
                RETURNING id, aggregate_id, event_type, payload::text, publish_attempts
                """)
                .param("id", id)
                .query(OutboxEntry.class)
                .single();
    }

    private UUID wardrobe(UUID id) {
        return jdbc.sql("SELECT wardrobe_id FROM garment WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .single();
    }

    private JsonNode node(JsonNode graph, UUID id) {
        for (var node : graph.path("nodes"))
            if (node.path("id").asText().equals(id.toString())) return node;
        throw new AssertionError("Expected garment in graph: " + id);
    }

    private List<String> ids(JsonNode graph) {
        var ids = new ArrayList<String>();
        graph.path("nodes").forEach(node -> ids.add(node.path("id").asText()));
        return ids;
    }

    private Set<String> edges(JsonNode graph) {
        var edges = new HashSet<String>();
        graph.path("edges").forEach(edge -> edges.add(pair(edge)));
        return edges;
    }

    private String pair(JsonNode edge) {
        return edge.path("source").asText() + ":" + edge.path("target").asText();
    }

    private String pair(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) < 0 ? a + ":" + b : b + ":" + a;
    }

    private JsonNode read(String owner, MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(
                mvc.perform(as(request, owner))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String owner) {
        return request.with(jwt().jwt(token -> token.subject(owner)));
    }
}
