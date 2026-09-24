package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.media.api.ObjectStoragePort;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxEntry;
import com.closetos.search.api.EmbeddingModel;
import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.EmbeddingProviderPort.VectorResult;
import com.closetos.search.application.EmbeddingGeneration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class RecommendationsHttpTest extends PostgresIntegrationTest {
    private static final String DUPLICATES = "/api/v1/insights/duplicates";
    private static final ModelInfo MODEL =
            new ModelInfo(
                    "6".repeat(64),
                    new EmbeddingModel(
                            "bedrock", "amazon.titan-embed-image-v1", "1", "multimodal-1", 256));
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired EmbeddingGeneration generation;
    @MockitoBean EmbeddingProviderPort provider;
    @MockitoBean ObjectStoragePort storage;

    @BeforeEach
    void modelAndSignedAssets() {
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
        doAnswer(invocation -> "https://storage.test/" + invocation.getArgument(0, String.class))
                .when(storage)
                .signDownload(any(), any());
    }

    @Test
    void authenticationBoundsAndSourceOwnershipAreCheckedBeforeProviderAccess() throws Exception {
        mvc.perform(get(DUPLICATES)).andExpect(status().isUnauthorized());
        String owner = "recommendations-validation";
        for (var request :
                List.of(
                        get(DUPLICATES).param("limit", "0"),
                        get(DUPLICATES).param("limit", "101"),
                        get(DUPLICATES).param("category", "invalid"),
                        get(works(UUID.randomUUID())).param("weather", "invalid"),
                        get(works(UUID.randomUUID())).param("season", "invalid"),
                        get(works(UUID.randomUUID())).param("formality", "x".repeat(61))))
            mvc.perform(as(request, owner)).andExpect(status().isBadRequest());
        verify(provider, never()).model();
        UUID source = piece(owner, "Private shirt", "TOP", "#45664c", 0, false);
        clearInvocations(provider);
        mvc.perform(as(get(works(source)), "recommendations-stranger"))
                .andExpect(status().isNotFound());
        jdbc.sql("UPDATE garment SET status = 'PACKED' WHERE id = :id")
                .param("id", source)
                .update();
        mvc.perform(as(get(works(source)), owner)).andExpect(status().isConflict());
        verify(provider, never()).model();
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void photoCandidatesAreOwnerScopedCategoryAndColourFilteredAndNeverAutomaticallyChanged()
            throws Exception {
        String owner = "recommendations-duplicates";
        UUID a = piece(owner, "Olive shirt", "TOP", "#45664c", 0, true);
        UUID b = piece(owner, "Sage shirt", "TOP", "#4a6955", .1, true);
        piece(owner, "Blue shirt", "TOP", "#0000ff", .02, true);
        piece(owner, "Olive shoes", "SHOES", "#45664c", .03, true);
        piece(owner, "Manual shirt", "TOP", "#45664c", 0, false);
        UUID review = piece(owner, "Unreviewed", "TOP", "#45664c", 0, true);
        UUID archive = piece(owner, "Archived", "TOP", "#45664c", 0, true);
        jdbc.sql("UPDATE garment SET processing_status = 'READY_FOR_REVIEW' WHERE id = :id")
                .param("id", review)
                .update();
        jdbc.sql("UPDATE garment SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", archive)
                .update();
        UUID foreign =
                piece(
                        "recommendations-other-owner",
                        "Foreign private shirt",
                        "TOP",
                        "#45664c",
                        0,
                        true);
        clearInvocations(provider);
        var result = read(owner, get(DUPLICATES));
        assertThat(result.path("reviewedGarmentCount").asInt()).isEqualTo(5);
        assertThat(result.path("photoGarmentCount").asInt()).isEqualTo(4);
        assertThat(result.path("candidatePairCount").asInt()).isEqualTo(1);
        assertThat(result.path("items")).hasSize(1);
        var pair = result.path("items").get(0);
        assertThat(
                        Set.of(
                                pair.path("first").path("id").asText(),
                                pair.path("second").path("id").asText()))
                .isEqualTo(Set.of(a.toString(), b.toString()));
        assertThat(pair.path("first").path("assets").path("cardUrl").asText())
                .startsWith("https://storage.test/");
        assertThat(result.path("embeddings").path("state").asText()).isEqualTo("READY");
        assertThat(read(owner, get(DUPLICATES).param("category", "SHOES")).path("items")).isEmpty();
        assertThat(result.toString())
                .doesNotContain(
                        foreign.toString(),
                        "Foreign private shirt",
                        "modelKey",
                        "sourceFingerprint");
        assertThat(read(owner, get("/api/v1/garments/" + a)).path("version").asLong()).isZero();
        verify(provider, never()).embed(any(), any());
    }

    @Test
    void editsAndModelChangesRemoveStaleVisualMatchesAndOutagesDoNotInventDuplicates()
            throws Exception {
        String owner = "recommendations-models";
        UUID a = piece(owner, "First shirt", "TOP", "#444444", 0, true);
        piece(owner, "Second shirt", "TOP", "#444444", .1, true);
        assertThat(read(owner, get(DUPLICATES)).path("items")).hasSize(1);
        send(
                owner,
                patch("/api/v1/garments/" + a),
                json.createObjectNode().put("version", 0).put("name", "Edited shirt"));
        var pending = read(owner, get(DUPLICATES));
        assertThat(pending.path("items")).isEmpty();
        assertThat(pending.path("embeddings").path("state").asText()).isEqualTo("PENDING");
        var changed =
                new ModelInfo(
                        "5".repeat(64),
                        new EmbeddingModel(
                                "bedrock",
                                "amazon.titan-embed-image-v1",
                                "2",
                                "multimodal-1",
                                384));
        doReturn(changed).when(provider).model();
        var migrated = read(owner, get(DUPLICATES));
        assertThat(migrated.path("items")).isEmpty();
        assertThat(migrated.path("embeddings").path("indexedCount").asInt()).isZero();
        doAnswer(
                        invocation -> {
                            throw new DomainException(503, "EMBEDDINGS_UNAVAILABLE", "Unavailable");
                        })
                .when(provider)
                .model();
        var unavailable = read(owner, get(DUPLICATES));
        assertThat(unavailable.path("items")).isEmpty();
        assertThat(unavailable.path("embeddings").path("state").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void pairingsUseOwnerHistoryAndActiveOutfitsWhileHardContextAndAvailabilityRemainMandatory()
            throws Exception {
        String owner = "recommendations-history";
        UUID source = piece(owner, "Winter shirt", "TOP", "#444444", 0, false);
        UUID trousers = piece(owner, "Winter trousers", "BOTTOM", "#444444", .1, false);
        UUID laundry = piece(owner, "Laundry trousers", "BOTTOM", "#444444", .02, false);
        UUID conflicting = piece(owner, "Second shirt", "TOP", "#444444", .02, false);
        UUID unknown = piece(owner, "Unknown context", "BOTTOM", "#444444", .01, false, false);
        UUID active = outfit(owner, source, trousers);
        UUID archived = outfit(owner, source, trousers);
        send(
                owner,
                patch("/api/v1/outfits/" + archived),
                json.createObjectNode().put("version", 0).put("archived", true));
        wear(owner, source, trousers);
        UUID removed = wear(owner, source, trousers);
        mvc.perform(as(delete("/api/v1/wear-events/" + removed), owner))
                .andExpect(status().isNoContent());
        wear(owner, source, laundry);
        jdbc.sql("UPDATE garment SET status = 'LAUNDRY' WHERE id = :id")
                .param("id", laundry)
                .update();
        UUID foreign =
                piece(
                        "recommendations-foreign-history",
                        "Foreign trousers",
                        "BOTTOM",
                        "#444444",
                        0,
                        false);
        clearInvocations(provider);
        var result =
                read(
                        owner,
                        get(works(source))
                                .param("season", "WINTER")
                                .param("formality", "casual")
                                .param("weather", "COLD"));
        assertThat(result.path("eligibleCount").asInt()).isEqualTo(1);
        assertThat(result.path("items")).hasSize(1);
        var item = result.path("items").get(0);
        assertThat(item.path("garment").path("id").asText()).isEqualTo(trousers.toString());
        assertThat(item.path("wornTogetherCount").asInt()).isEqualTo(1);
        assertThat(item.path("savedTogetherCount").asInt()).isEqualTo(1);
        assertThat(item.path("semanticAffinityKnown").asBoolean()).isTrue();
        assertThat(result.toString())
                .doesNotContain(
                        foreign.toString(), unknown.toString(), conflicting.toString(), "modelKey");
        var mismatch = read(owner, get(works(source)).param("season", "SUMMER"));
        assertThat(mismatch.path("sourceMatchesContext").asBoolean()).isFalse();
        assertThat(mismatch.path("items")).isEmpty();
        doAnswer(
                        invocation -> {
                            throw new DomainException(503, "EMBEDDINGS_UNAVAILABLE", "Unavailable");
                        })
                .when(provider)
                .model();
        var fallback = read(owner, get(works(source)).param("season", "WINTER"));
        assertThat(fallback.path("embeddings").path("state").asText()).isEqualTo("UNAVAILABLE");
        for (var candidate : fallback.path("items"))
            assertThat(candidate.path("semanticAffinityKnown").asBoolean()).isFalse();
        assertThat(fallback.path("items")).hasSize(1);
        verify(provider, never()).embed(any(), any());
        assertThat(read(owner, get("/api/v1/outfits/" + active)).path("archived").asBoolean())
                .isFalse();
    }

    private UUID piece(
            String owner, String name, String category, String colour, double angle, boolean photo)
            throws Exception {
        return piece(owner, name, category, colour, angle, photo, true);
    }

    private UUID piece(
            String owner,
            String name,
            String category,
            String colour,
            double angle,
            boolean photo,
            boolean contextKnown)
            throws Exception {
        var body =
                json.createObjectNode()
                        .put("name", name)
                        .put("category", category)
                        .put("primaryColourHex", colour);
        if (contextKnown) {
            body.put("formality", "Casual");
            body.set("seasonTags", json.createArrayNode().add("winter"));
            body.set("styleTags", json.createArrayNode().add("cold-weather").add("classic"));
        }
        var created = send(owner, post("/api/v1/garments"), body);
        UUID id = UUID.fromString(created.path("id").asText());
        if (photo) photo(id);
        var vector = new ArrayList<>(Collections.nCopies(256, 0.0));
        vector.set(0, Math.cos(angle));
        vector.set(1, Math.sin(angle));
        doAnswer(
                        invocation -> {
                            EmbeddingProviderPort.Input input = invocation.getArgument(0);
                            if (photo) assertThat(input.imageKey()).isNotBlank();
                            else assertThat(input.imageKey()).isNull();
                            return new VectorResult(MODEL.modelKey(), MODEL.model(), vector);
                        })
                .when(provider)
                .embed(any(), any());
        generation.generate(claim(id));
        return id;
    }

    private void photo(UUID garment) {
        UUID image = UUID.randomUUID();
        var assets = json.createObjectNode();
        for (String role : List.of("isolated", "display", "card", "thumbnail"))
            assets.set(
                    role,
                    json.createObjectNode()
                            .put("key", "test/" + garment + "/" + image + "/" + role)
                            .put("checksumSha256", "7".repeat(64))
                            .put("size", 100)
                            .put("width", 100)
                            .put("height", 200));
        jdbc.sql(
                        """
                INSERT INTO garment_image(id, garment_id, wardrobe_id, user_id, image_role, original_filename,
                    mime_type, source_s3_key, expected_size, source_checksum, upload_key, processing_status, assets)
                SELECT :image, g.id, g.wardrobe_id, w.owner_id, 'FRONT', 'photo.png', 'image/png', :source,
                    100, :checksum, :upload, 'READY', CAST(:assets AS jsonb)
                FROM garment g JOIN wardrobe w ON w.id = g.wardrobe_id WHERE g.id = :garment
                """)
                .param("image", image)
                .param("source", "test/" + image + "/original.png")
                .param("checksum", "A".repeat(43) + "=")
                .param("upload", UUID.randomUUID())
                .param("assets", assets.toString())
                .param("garment", garment)
                .update();
    }

    private OutboxEntry claim(UUID garment) {
        return jdbc.sql(
                        """
                UPDATE outbox_event SET publish_attempts = publish_attempts + 1, lease_until = now() + interval '6 minutes'
                WHERE id = (SELECT id FROM outbox_event WHERE aggregate_id = :id AND event_type = 'GENERATE_EMBEDDING'
                    AND published_at IS NULL ORDER BY created_at LIMIT 1)
                RETURNING id, aggregate_id, event_type, payload::text, publish_attempts
                """)
                .param("id", garment)
                .query(OutboxEntry.class)
                .single();
    }

    private UUID outfit(String owner, UUID first, UUID second) throws Exception {
        var body = json.createObjectNode().put("name", "Recorded pairing");
        var items = json.createArrayNode();
        for (UUID garment : List.of(first, second))
            items.add(
                    json.createObjectNode()
                            .put("garmentId", garment.toString())
                            .put("x", 25)
                            .put("y", 25)
                            .put("scale", 1)
                            .put("rotation", 0)
                            .put("zIndex", items.size()));
        body.set("items", items);
        return UUID.fromString(send(owner, post("/api/v1/outfits"), body).path("id").asText());
    }

    private UUID wear(String owner, UUID first, UUID second) throws Exception {
        var body = json.createObjectNode().put("wornOn", LocalDate.now().toString());
        body.set("garmentIds", json.createArrayNode().add(first.toString()).add(second.toString()));
        return UUID.fromString(
                send(
                                owner,
                                post("/api/v1/wear-events")
                                        .header("Idempotency-Key", UUID.randomUUID()),
                                body)
                        .path("id")
                        .asText());
    }

    private JsonNode send(String owner, MockHttpServletRequestBuilder request, ObjectNode body)
            throws Exception {
        var response =
                mvc.perform(
                                as(request, owner)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body.toString()))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isIn(200, 201);
        return json.readTree(response.getContentAsString());
    }

    private JsonNode read(String owner, MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(
                mvc.perform(as(request, owner))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private String works(UUID garment) {
        return "/api/v1/garments/" + garment + "/works-with";
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String owner) {
        return request.with(jwt().jwt(token -> token.subject(owner)));
    }
}
