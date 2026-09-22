package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class SearchHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired Clock clock;
    @Autowired EmbeddingGeneration generation;
    @MockitoBean EmbeddingProviderPort provider;
    final ModelInfo model =
            new ModelInfo(
                    "c".repeat(64),
                    new EmbeddingModel(
                            "bedrock", "amazon.titan-embed-image-v1", "1", "multimodal-1", 256));

    @BeforeEach
    void embeddings() {
        when(provider.model()).thenReturn(model);
        queryVector();
    }

    @Test
    void statusChangesDuringInferenceAreIncludedInTheHardFilterSnapshot() throws Exception {
        String owner = "search-inference-status";
        UUID garment = piece(owner, "Quiet knit", "TOP", null, null);
        doAnswer(
                        invocation -> {
                            java.util.concurrent.CompletableFuture.runAsync(
                                            () -> {
                                                try {
                                                    write(
                                                            owner,
                                                            post(
                                                                    "/api/v1/garments/"
                                                                            + garment
                                                                            + "/status"),
                                                            json.createObjectNode()
                                                                    .put("version", 0)
                                                                    .put("status", "LAUNDRY"),
                                                            200);
                                                } catch (Exception exception) {
                                                    throw new IllegalStateException(exception);
                                                }
                                            })
                                    .join();
                            return vector(model, 1);
                        })
                .when(provider)
                .embed(any(), any());
        var page =
                read(
                        owner,
                        get("/api/v1/search")
                                .param("q", "relaxed silhouette")
                                .param("mode", "SEMANTIC")
                                .param("status", "AVAILABLE"));
        assertThat(ids(page)).isEmpty();
        assertThat(page.path("eligibleCount").asInt()).isZero();
    }

    @Test
    void oneThousandVectorsPaginateExactlyAndWarmSearchStaysWithinTheLatencyTarget()
            throws Exception {
        String owner = "search-thousand";
        UUID seed = piece(owner, "Seed garment", "TOP", null, null);
        UUID wardrobe =
                jdbc.sql("SELECT wardrobe_id FROM garment WHERE id = :id")
                        .param("id", seed)
                        .query(UUID.class)
                        .single();
        jdbc.sql(
                        """
                INSERT INTO garment(id, wardrobe_id, name, category, brand, created_at, updated_at)
                SELECT gen_random_uuid(), :wardrobe, 'Silhouette ' || n, 'TOP', 'Performance', now(), now()
                FROM generate_series(1, 1000) n
                """)
                .param("wardrobe", wardrobe)
                .update();
        String encoded =
                "["
                        + String.join(
                                ",",
                                vector(model, 1).vector().stream().map(Object::toString).toList())
                        + "]";
        jdbc.sql(
                        """
                INSERT INTO garment_embedding(garment_id, wardrobe_id, model_key, dimensions, embedding, source_fingerprint, updated_at)
                SELECT id, wardrobe_id, :model, 256, CAST(:vector AS vector), :fingerprint, now()
                FROM garment WHERE wardrobe_id = :wardrobe AND brand = 'Performance'
                """)
                .param("model", model.modelKey())
                .param("vector", encoded)
                .param("fingerprint", "e".repeat(64))
                .param("wardrobe", wardrobe)
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
        var found = new HashSet<String>();
        String cursor = null;
        do {
            var request =
                    get("/api/v1/search")
                            .param("mode", "SEMANTIC")
                            .param("q", "quiet silhouette")
                            .param("brand", "Performance")
                            .param("limit", "100");
            if (cursor != null) request.param("cursor", cursor);
            var page = read(owner, request);
            assertThat(page.path("eligibleCount").asInt()).isEqualTo(1000);
            assertThat(page.path("indexedCount").asInt()).isEqualTo(1000);
            for (String id : ids(page)) assertThat(found.add(id)).isTrue();
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(found).hasSize(1000);
        var timings = new ArrayList<Double>();
        for (int request = 0; request < 20; request++) {
            long started = System.nanoTime();
            read(
                    owner,
                    get("/api/v1/search")
                            .param("mode", "SEMANTIC")
                            .param("q", "quiet silhouette")
                            .param("brand", "Performance"));
            timings.add((System.nanoTime() - started) / 1_000_000.0);
        }
        timings.sort(Double::compare);
        double p95 = timings.get(18);
        System.out.printf("Warm search over 1000 vectors: p95 %.1f ms%n", p95);
        assertThat(p95).isLessThan(500);
    }

    @Test
    void naturalConstraintsAreHardSqlFiltersEvenForAnIdenticalVector() throws Exception {
        String owner = "search-constraints";
        var never = piece(owner, "Evening silk", "DRESS", "Black", "Formal");
        var old = piece(owner, "Evening satin", "DRESS", "Black", "Formal");
        var recent = piece(owner, "Evening crepe", "DRESS", "Black", "Formal");
        piece(owner, "Casual cotton", "DRESS", "Black", "Casual");
        piece(owner, "Evening shoes", "SHOES", "Black", "Formal");
        piece(owner, "Evening red", "DRESS", "Red", "Formal");
        LocalDate today = LocalDate.now(clock);
        jdbc.sql("UPDATE garment SET last_worn_at = :date WHERE id = :id")
                .param("date", today.minusDays(31))
                .param("id", old)
                .update();
        jdbc.sql("UPDATE garment SET last_worn_at = :date WHERE id = :id")
                .param("date", today.minusDays(2))
                .param("id", recent)
                .update();
        var query =
                get("/api/v1/search")
                        .param("q", "black formal dress like this but not worn recently");
        var page = read(owner, query);
        assertThat(ids(page)).containsExactlyInAnyOrder(never.toString(), old.toString());
        assertThat(page.path("eligibleCount").asInt()).isEqualTo(2);
        assertThat(page.path("appliedConstraints").size()).isEqualTo(4);
        assertThat(page.path("items").get(0).path("explanations").toString())
                .contains("past 30 days", "black", "formal");
        assertThat(
                        ids(
                                read(
                                        owner,
                                        get("/api/v1/search")
                                                .param("q", "black formal dress")
                                                .param("category", "SHOES"))))
                .isEmpty();
    }

    @Test
    void semanticRankingWorksWithoutWordOverlapAndHybridAddsLexicalEvidence() throws Exception {
        String owner = "search-ranking";
        UUID closest = piece(owner, "Studio one", "TOP", null, null, 1, 0);
        UUID related = piece(owner, "Studio two", "TOP", null, null, .8, .6);
        UUID literal = piece(owner, "Elegant silhouette", "TOP", null, null, 0, 1);
        queryVector();
        var semantic =
                read(
                        owner,
                        get("/api/v1/search")
                                .param("q", "elegant silhouette")
                                .param("mode", "SEMANTIC"));
        assertThat(ids(semantic))
                .containsExactly(closest.toString(), related.toString(), literal.toString());
        var hybrid =
                read(
                        owner,
                        get("/api/v1/search")
                                .param("q", "elegant silhouette")
                                .param("mode", "HYBRID"));
        assertThat(ids(hybrid).getFirst()).isEqualTo(literal.toString());
        assertThat(hybrid.path("items").get(0).path("explanations").toString())
                .contains("Matches words", "Related to your description");
        assertThat(hybrid.toString()).doesNotContain("score", "vector", "modelKey", "percentage");
        clearInvocations(provider);
        read(
                owner,
                get("/api/v1/search").param("q", "elegant silhouette").param("mode", "SEMANTIC"));
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void similarExcludesItsSourceRespectsAvailabilityAndNeverCrossesOwnersOrModels()
            throws Exception {
        String owner = "search-similar";
        UUID source = piece(owner, "First knit", "TOP", null, null);
        UUID available = piece(owner, "Second knit", "TOP", null, null);
        UUID laundry = piece(owner, "Laundry knit", "TOP", null, null);
        jdbc.sql("UPDATE garment SET status = 'LAUNDRY' WHERE id = :id")
                .param("id", laundry)
                .update();
        var page =
                read(
                        owner,
                        get("/api/v1/garments/" + source + "/similar")
                                .param("status", "AVAILABLE"));
        assertThat(ids(page)).containsExactly(available.toString());
        assertThat(page.path("items").get(0).path("explanations").toString())
                .contains("selected piece");
        mvc.perform(as(get("/api/v1/garments/" + source + "/similar"), "search-stranger"))
                .andExpect(status().isNotFound());
        var changed =
                new ModelInfo(
                        "d".repeat(64),
                        new EmbeddingModel(
                                "bedrock",
                                "amazon.titan-embed-image-v1",
                                "2",
                                "multimodal-2",
                                384));
        when(provider.model()).thenReturn(changed);
        queryVector();
        var newSpace =
                read(owner, get("/api/v1/search").param("mode", "SEMANTIC").param("q", "knit"));
        assertThat(ids(newSpace)).isEmpty();
        assertThat(newSpace.path("indexedCount").asInt()).isZero();
        mvc.perform(as(get("/api/v1/garments/" + source + "/similar"), owner))
                .andExpect(status().isConflict());
    }

    @Test
    void metadataEditsHideStaleVectorsUntilSuccessfulRegeneration() throws Exception {
        String owner = "search-stale";
        UUID garment = piece(owner, "Original shirt", "TOP", null, null);
        write(
                owner,
                patch("/api/v1/garments/" + garment),
                json.createObjectNode().put("version", 0).put("name", "Corrected shirt"),
                200);
        assertThat(
                        ids(
                                read(
                                        owner,
                                        get("/api/v1/search")
                                                .param("mode", "SEMANTIC")
                                                .param("q", "shirt"))))
                .isEmpty();
        var hybrid = read(owner, get("/api/v1/search").param("q", "Corrected shirt"));
        assertThat(ids(hybrid)).containsExactly(garment.toString());
        assertThat(hybrid.path("items").get(0).path("explanations").toString())
                .doesNotContain("Related to");
        generation.generate(claim(garment));
        assertThat(
                        ids(
                                read(
                                        owner,
                                        get("/api/v1/search")
                                                .param("mode", "SEMANTIC")
                                                .param("q", "shirt"))))
                .containsExactly(garment.toString());
    }

    @Test
    void keywordAndFilterSearchWorkWhenEmbeddingsAreUnavailable() throws Exception {
        String owner = "search-unavailable";
        UUID garment = piece(owner, "Tailored linen", "TOP", "Cream", null);
        when(provider.model())
                .thenThrow(new DomainException(503, "EMBEDDINGS_UNAVAILABLE", "Unavailable"));
        clearInvocations(provider);
        assertThat(
                        ids(
                                read(
                                        owner,
                                        get("/api/v1/search")
                                                .param("mode", "KEYWORD")
                                                .param("q", "tailored"))))
                .containsExactly(garment.toString());
        assertThat(ids(read(owner, get("/api/v1/search").param("colour", "Cream"))))
                .containsExactly(garment.toString());
        verify(provider, never()).model();
        mvc.perform(
                        as(
                                get("/api/v1/search")
                                        .param("mode", "SEMANTIC")
                                        .param("q", "silhouette"),
                                owner))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void imageQueryIsBoundedScopedAndCombinedWithHardFilters() throws Exception {
        String owner = "search-image";
        UUID top = piece(owner, "Studio top", "TOP", null, null);
        piece(owner, "Studio shoes", "SHOES", null, null);
        String image =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                java.nio.file.Files.readAllBytes(
                                        java.nio.file.Path.of("../../e2e/fixtures/shirt.png")));
        queryVector();
        clearInvocations(provider);
        var request =
                json.createObjectNode()
                        .put("imageBase64", image)
                        .put("mode", "SEMANTIC")
                        .set("filters", json.createObjectNode().put("category", "TOP"));
        var response = write(owner, post("/api/v1/search/image"), request, 200);
        assertThat(ids(response)).containsExactly(top.toString());
        verify(provider).embed(new EmbeddingProviderPort.Input(null, null, null, image), model);
        assertThat(response.toString()).doesNotContain(image);
        write(
                owner,
                post("/api/v1/search/image"),
                json.createObjectNode().put("imageBase64", "invalid?"),
                400);
        write(
                owner,
                post("/api/v1/search/image"),
                json.createObjectNode()
                        .put("imageBase64", image)
                        .set("filters", json.createObjectNode().put("limit", 101)),
                400);
        read(
                "search-image-other",
                get("/api/v1/search").param("mode", "SEMANTIC").param("q", "unique description"));
        read(
                owner,
                get("/api/v1/search").param("mode", "SEMANTIC").param("q", "unique description"));
        verify(provider, times(3)).embed(any(), any());
    }

    @Test
    void equalScoresPaginateWithoutGapsAndCursorsBindToOwnerModeAndFilters() throws Exception {
        String owner = "search-cursors";
        var expected = new HashSet<String>();
        for (int index = 0; index < 7; index++)
            expected.add(piece(owner, "Studio " + index, "TOP", null, null).toString());
        var first =
                read(
                        owner,
                        get("/api/v1/search")
                                .param("mode", "SEMANTIC")
                                .param("q", "soft silhouette")
                                .param("limit", "2"));
        String firstCursor = first.path("nextCursor").asText();
        var found = new HashSet<String>(ids(first));
        var current = first;
        while (!current.path("nextCursor").isNull()) {
            current =
                    read(
                            owner,
                            get("/api/v1/search")
                                    .param("mode", "SEMANTIC")
                                    .param("q", "soft silhouette")
                                    .param("limit", "3")
                                    .param("cursor", current.path("nextCursor").asText()));
            for (String id : ids(current)) assertThat(found.add(id)).isTrue();
        }
        assertThat(found).isEqualTo(expected);
        for (var request :
                List.of(
                        get("/api/v1/search")
                                .param("mode", "HYBRID")
                                .param("q", "soft silhouette")
                                .param("cursor", firstCursor),
                        get("/api/v1/search")
                                .param("mode", "SEMANTIC")
                                .param("q", "soft silhouette")
                                .param("colour", "Black")
                                .param("cursor", firstCursor),
                        get("/api/v1/search")
                                .param("mode", "SEMANTIC")
                                .param("q", "soft silhouette")
                                .param("cursor", "malformed")))
            mvc.perform(as(request, owner)).andExpect(status().isBadRequest());
        mvc.perform(
                        as(
                                get("/api/v1/search")
                                        .param("mode", "SEMANTIC")
                                        .param("q", "soft silhouette")
                                        .param("cursor", firstCursor),
                                "search-cursor-other"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void naturalExclusionsAndUntrustedValuesRemainParameterBound() throws Exception {
        String owner = "search-negative";
        UUID white = piece(owner, "Studio light", "DRESS", "White", "Formal");
        piece(owner, "Studio dark", "DRESS", "Black", "Formal");
        piece(owner, "Studio footwear", "SHOES", "White", "Formal");
        assertThat(ids(read(owner, get("/api/v1/search").param("q", "formal dress not black"))))
                .containsExactly(white.toString());
        assertThat(
                        ids(
                                read(
                                        owner,
                                        get("/api/v1/search")
                                                .param("mode", "SEMANTIC")
                                                .param("q", "' OR 1=1 --")
                                                .param("brand", "' OR 1=1 --"))))
                .isEmpty();
        mvc.perform(get("/api/v1/search")).andExpect(status().isUnauthorized());
        for (var request :
                List.of(
                        get("/api/v1/search").param("mode", "SEMANTIC"),
                        get("/api/v1/search").param("q", "a".repeat(501)),
                        get("/api/v1/search").param("limit", "101"),
                        get("/api/v1/search").param("q", "shirt").param("mode", "FILTERS")))
            mvc.perform(as(request, owner)).andExpect(status().isBadRequest());
    }

    private UUID piece(
            String owner,
            String name,
            String category,
            String colour,
            String formality,
            double... coordinates)
            throws Exception {
        var body = json.createObjectNode().put("name", name).put("category", category);
        if (colour != null) body.put("primaryColourName", colour);
        if (formality != null) body.put("formality", formality);
        UUID id =
                UUID.fromString(
                        write(owner, post("/api/v1/garments"), body, 201).path("id").asText());
        doReturn(vector(model, coordinates.length == 0 ? new double[] {1} : coordinates))
                .when(provider)
                .embed(any(), any());
        generation.generate(claim(id));
        queryVector();
        return id;
    }

    private void queryVector() {
        doAnswer(invocation -> vector(invocation.getArgument(1), 1))
                .when(provider)
                .embed(any(), any());
    }

    private VectorResult vector(ModelInfo info, double... coordinates) {
        var vector =
                new ArrayList<Double>(
                        java.util.Collections.nCopies(info.model().dimensions(), 0.0));
        for (int index = 0; index < coordinates.length; index++)
            vector.set(index, coordinates[index]);
        return new VectorResult(info.modelKey(), info.model(), vector);
    }

    private OutboxEntry claim(UUID id) {
        return jdbc.sql(
                        """
                UPDATE outbox_event SET publish_attempts = publish_attempts + 1, lease_until = now() + interval '6 minutes'
                WHERE id = (SELECT id FROM outbox_event WHERE aggregate_id = :id AND event_type = 'GENERATE_EMBEDDING'
                    AND published_at IS NULL ORDER BY created_at LIMIT 1)
                RETURNING id, aggregate_type, aggregate_id, event_type, payload::text, publish_attempts
                """)
                .param("id", id)
                .query(OutboxEntry.class)
                .single();
    }

    private List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>();
        page.path("items").forEach(hit -> ids.add(hit.path("garment").path("id").asText()));
        return ids;
    }

    private JsonNode read(String owner, MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(
                mvc.perform(as(request, owner))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private JsonNode write(
            String owner, MockHttpServletRequestBuilder request, ObjectNode data, int expected)
            throws Exception {
        return json.readTree(
                mvc.perform(
                                as(request, owner)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsBytes(data)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String owner) {
        return request.with(jwt().jwt(token -> token.subject(owner)));
    }
}
