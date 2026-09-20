package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.activity.application.ProcessingResults;
import com.closetos.activity.application.ProcessingTransitions;
import com.closetos.media.api.AssetDescriptor;
import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.ObjectStoragePort;
import com.closetos.media.api.ObjectStoragePort.StoredObject;
import com.closetos.media.api.ProcessingAccess;
import com.closetos.media.api.ProcessingResult;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.media.api.UploadInstructions;
import com.closetos.media.api.WorkflowJob;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.OutboxAccess;
import com.closetos.platform.api.OutboxQueue;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class MediaHttpTest extends PostgresIntegrationTest {
    private static final String CHECKSUM = Base64.getEncoder().encodeToString(new byte[32]);
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ProcessingAccess processing;
    @Autowired ProcessingTransitions transitions;
    @Autowired ProcessingResults results;
    @Autowired OutboxAccess outbox;
    @Autowired OutboxQueue queue;
    @Autowired TransactionTemplate transaction;
    @MockitoBean ObjectStoragePort storage;

    @BeforeEach
    void signedStorage() {
        when(storage.signUpload(any()))
                .thenAnswer(
                        invocation -> {
                            ImageRecord image = invocation.getArgument(0);
                            return new UploadInstructions(
                                    "https://storage.test/" + image.sourceS3Key(),
                                    "PUT",
                                    Map.of("x-amz-checksum-sha256", image.sourceChecksum()),
                                    Instant.now().plusSeconds(600));
                        });
        when(storage.signDownload(any(), any()))
                .thenAnswer(invocation -> "https://storage.test/" + invocation.getArgument(0));
    }

    @Test
    void reservationIsAtomicOwnerScopedAndIdempotent() throws Exception {
        String owner = "upload-reservation";
        UUID key = UUID.randomUUID();
        JsonNode first = upload(owner, key, body("image/png", 1024));
        JsonNode replay = upload(owner, key, body("image/png", 1024));
        assertThat(replay.path("garmentId").asText()).isEqualTo(first.path("garmentId").asText());
        assertThat(replay.path("imageId").asText()).isEqualTo(first.path("imageId").asText());
        String garment = first.path("garmentId").asText();
        String image = first.path("imageId").asText();
        assertThat(first.path("upload").path("url").asText())
                .contains("/garments/" + garment + "/images/" + image + "/original.png");
        mvc.perform(as(get("/api/v1/garments/" + garment), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("AWAITING_UPLOAD"));
        mvc.perform(
                        as(post("/api/v1/garments/uploads"), owner)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("image/png", 2048)))
                .andExpect(status().isConflict());
        for (String path :
                new String[] {
                    "/api/v1/processing/" + image, "/api/v1/garments/" + garment + "/images"
                }) mvc.perform(as(get(path), "upload-intruder")).andExpect(status().isNotFound());
        mvc.perform(
                        as(post("/api/v1/garments/" + garment + "/images"), "upload-intruder")
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("image/png", 1024)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        as(
                                delete("/api/v1/garments/" + garment + "/images/" + image),
                                "upload-intruder"))
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.sql("SELECT count(*) FROM garment_image WHERE garment_id = :id")
                                .param("id", UUID.fromString(garment))
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void invalidUploadRollsBackItsDraftAndMissingKeyReturnsClientError() throws Exception {
        String owner = "upload-invalid";
        mvc.perform(as(get("/api/v1/wardrobes/current"), owner)).andExpect(status().isOk());
        for (String request :
                new String[] {
                    body("application/pdf", 1024),
                    body("image/png", 26_214_401),
                    body("image/png", 0)
                }) {
            mvc.perform(
                            as(post("/api/v1/garments/uploads"), owner)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(request))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        as(post("/api/v1/garments/uploads"), owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("image/png", 1024)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/garments"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void duplicateUploadAndResultEventsCreateOneJobAndNeverOverwriteManualMetadata()
            throws Exception {
        Fixture fixture = uploaded("upload-events");
        transitions.uploaded(fixture.image(), "duplicate-upload:" + fixture.image().id());
        assertThat(jobCount(fixture.image().id())).isEqualTo(1);
        ProcessingResult result = verifiedResult(fixture.job());
        results.accept(result, "result:" + fixture.job().jobId());
        results.accept(result, "result:" + fixture.job().jobId());
        results.accept(result, "redelivered-result:" + fixture.job().jobId());
        var snapshot = processing.snapshot(fixture.image().id(), fixture.image().wardrobeId());
        assertThat(snapshot.state()).isEqualTo(ProcessingStatus.READY_FOR_REVIEW);
        assertThat(snapshot.canRetry()).isFalse();
        mvc.perform(as(get("/api/v1/garments/" + fixture.image().garmentId()), "upload-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New piece"))
                .andExpect(jsonPath("$.category").value("OTHER"))
                .andExpect(jsonPath("$.assets.thumbnailUrl").isNotEmpty());
        assertThatThrownBy(
                        () -> transitions.retry(fixture.image().id(), fixture.image().wardrobeId()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void retryKeepsImageAndGarmentAndIgnoresLateFailuresAndResults() throws Exception {
        Fixture fixture = uploaded("upload-retry");
        transitions.failed(fixture.job().jobId(), "TEST_FAILURE", "Try another photograph.");
        WorkflowJob retry = transitions.retry(fixture.image().id(), fixture.image().wardrobeId());
        assertThat(retry.imageId()).isEqualTo(fixture.job().imageId());
        assertThat(retry.garmentId()).isEqualTo(fixture.job().garmentId());
        assertThat(retry.executionName()).isNotEqualTo(fixture.job().executionName());
        assertThat(transitions.started(fixture.job(), "old-execution")).isFalse();
        transitions.failed(fixture.job().jobId(), "LATE_FAILURE", "Old failure");
        results.accept(verifiedResult(fixture.job()), "late-result:" + fixture.job().jobId());
        assertThat(processing.snapshot(retry.imageId(), retry.wardrobeId()).state())
                .isEqualTo(ProcessingStatus.UPLOADED);
        for (int attempt = 2; attempt <= 5; attempt++) {
            transitions.failed(retry.jobId(), "TEST_FAILURE", "Try again.");
            if (attempt < 5) retry = transitions.retry(retry.imageId(), retry.wardrobeId());
        }
        assertThat(processing.snapshot(retry.imageId(), retry.wardrobeId()).canRetry()).isFalse();
        assertThatThrownBy(
                        () -> transitions.retry(fixture.image().id(), fixture.image().wardrobeId()))
                .isInstanceOf(DomainException.class);
        assertThat(jobCount(fixture.image().id())).isEqualTo(5);
    }

    @Test
    void originalDeletionIsEnqueuedOnlyAfterEveryDerivativePassesVerification() throws Exception {
        String owner = "upload-privacy";
        mvc.perform(as(get("/api/v1/wardrobes/current"), owner)).andExpect(status().isOk());
        jdbc.sql(
                        "UPDATE user_profile SET delete_original_after_isolation = true WHERE cognito_sub = :subject")
                .param("subject", owner)
                .update();
        Fixture fixture = uploaded(owner);
        ProcessingResult result = verifiedResult(fixture.job());
        when(storage.head(result.assets().get("thumbnail").key()))
                .thenReturn(Optional.of(new StoredObject(1024, "image/webp", "wrong-checksum")));
        assertThatThrownBy(() -> results.accept(result, "bad-result:" + fixture.job().jobId()))
                .isInstanceOf(DomainException.class);
        assertThat(deletionCount(fixture.image().id())).isZero();
        assertThat(processing.snapshot(fixture.image().id(), fixture.image().wardrobeId()).state())
                .isEqualTo(ProcessingStatus.UPLOADED);
        verifiedResult(fixture.job());
        results.accept(result, "good-result:" + fixture.job().jobId());
        assertThat(deletionCount(fixture.image().id())).isEqualTo(1);
    }

    @Test
    void outboxRollsBackWithTransactionAndLeasesExcludeDuplicateClaims() {
        UUID aggregate = UUID.randomUUID();
        transaction.executeWithoutResult(
                status -> {
                    outbox.enqueue(
                            "test",
                            aggregate,
                            "TEST",
                            "rollback:" + aggregate,
                            Map.of("id", aggregate));
                    status.setRollbackOnly();
                });
        assertThat(
                        jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :id")
                                .param("id", aggregate)
                                .query(Integer.class)
                                .single())
                .isZero();
        transaction.executeWithoutResult(
                status ->
                        outbox.enqueue(
                                "test",
                                aggregate,
                                "TEST",
                                "committed:" + aggregate,
                                Map.of("id", aggregate)));
        var first = queue.claim();
        var second = queue.claim();
        assertThat(first).hasSize(1);
        assertThat(second).noneMatch(event -> event.id().equals(first.getFirst().id()));
        queue.published(first.getFirst());
        for (var event : second) queue.failed(event, "Test retry", false);
    }

    @Test
    void analysisStaysSeparateUntilAnOwnerAcceptsOrCorrectsIt() throws Exception {
        String owner = "suggestion-accept";
        Fixture fixture = analysed(owner, null);
        String path = "/api/v1/garments/" + fixture.image().garmentId();
        mvc.perform(as(get(path), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New piece"))
                .andExpect(jsonPath("$.category").value("OTHER"))
                .andExpect(jsonPath("$.material").isEmpty());
        mvc.perform(as(get("/api/v1/garments").param("category", "TOP"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        JsonNode suggestion = response(as(get(path + "/suggestions"), owner)).get(0);
        assertThat(suggestion.path("modelId").asText()).isEqualTo("test-analysis-model");
        assertThat(suggestion.path("modelVersion").asText()).isEqualTo("2026-test");
        assertThat(suggestion.path("promptVersion").asText()).isEqualTo("garment-metadata-2");
        assertThat(
                        suggestion
                                .path("suggestions")
                                .path("materialEstimate")
                                .path("confidence")
                                .asDouble())
                .isEqualTo(.61);
        long version = response(as(get(path), owner)).path("version").asLong();
        ObjectNode request =
                json.createObjectNode()
                        .put("suggestionId", suggestion.path("id").asText())
                        .put("suggestionVersion", 0)
                        .put("garmentVersion", version);
        request.set(
                "corrections",
                json.createObjectNode()
                        .put("name", "Favourite olive shirt")
                        .put("material", "Cotton"));
        mvc.perform(
                        as(post(path + "/suggestions/accept"), owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Favourite olive shirt"))
                .andExpect(jsonPath("$.category").value("TOP"))
                .andExpect(jsonPath("$.primaryColourName").value("Olive"))
                .andExpect(jsonPath("$.material").value("Cotton"))
                .andExpect(jsonPath("$.processingStatus").value("READY"));
        mvc.perform(
                        as(
                                get("/api/v1/garments")
                                        .param("category", "TOP")
                                        .param("colour", "Olive")
                                        .param("tag", "Minimal"),
                                owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Favourite olive shirt"));
        mvc.perform(as(get(path + "/suggestions"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[0].acceptedAt").isNotEmpty());
        mvc.perform(
                        as(post(path + "/suggestions/accept"), owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request.toString()))
                .andExpect(status().isConflict());
        assertThat(processing.snapshot(fixture.image().id(), fixture.image().wardrobeId()).state())
                .isEqualTo(ProcessingStatus.READY);
    }

    @Test
    void rejectionAndStaleAcceptanceCannotChangeCanonicalDetails() throws Exception {
        String owner = "suggestion-stale";
        Fixture fixture = analysed(owner, null);
        String path = "/api/v1/garments/" + fixture.image().garmentId();
        JsonNode suggestion = response(as(get(path + "/suggestions"), owner)).get(0);
        long version = response(as(get(path), owner)).path("version").asLong();
        mvc.perform(
                        as(patch(path), owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.createObjectNode()
                                                .put("name", "My own details")
                                                .put("version", version)
                                                .toString()))
                .andExpect(status().isOk());
        ObjectNode request =
                json.createObjectNode()
                        .put("suggestionId", suggestion.path("id").asText())
                        .put("suggestionVersion", 0)
                        .put("garmentVersion", version);
        request.set("corrections", json.createObjectNode());
        mvc.perform(
                        as(post(path + "/suggestions/accept"), owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request.toString()))
                .andExpect(status().isConflict());
        mvc.perform(as(get(path + "/suggestions"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        request.remove("garmentVersion");
        request.remove("corrections");
        mvc.perform(
                        as(post(path + "/suggestions/reject"), owner)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request.toString()))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(path), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My own details"))
                .andExpect(jsonPath("$.category").value("OTHER"));
        mvc.perform(as(get(path + "/suggestions"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REJECTED"));
    }

    @Test
    void suggestionReadsAndDecisionsCannotCrossOwnersOrGarments() throws Exception {
        Fixture fixture = analysed("suggestion-owner", null);
        String path = "/api/v1/garments/" + fixture.image().garmentId();
        JsonNode suggestion = response(as(get(path + "/suggestions"), "suggestion-owner")).get(0);
        mvc.perform(as(get(path + "/suggestions"), "suggestion-intruder"))
                .andExpect(status().isNotFound());
        ObjectNode reject =
                json.createObjectNode()
                        .put("suggestionId", suggestion.path("id").asText())
                        .put("suggestionVersion", 0);
        mvc.perform(
                        as(post(path + "/suggestions/reject"), "suggestion-intruder")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reject.toString()))
                .andExpect(status().isNotFound());
        Fixture own = uploaded("suggestion-intruder");
        mvc.perform(
                        as(
                                        post(
                                                "/api/v1/garments/"
                                                        + own.image().garmentId()
                                                        + "/suggestions/reject"),
                                        "suggestion-intruder")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reject.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidAnalysisPreservesVerifiedPhotoAndManualReview() throws Exception {
        for (String mutation :
                new String[] {
                    "confidence",
                    "category",
                    "extraField",
                    "missingField",
                    "wrongImage",
                    "coercedValue",
                    "wrongKey"
                }) {
            String owner = "analysis-invalid-" + mutation;
            Fixture fixture = analysed(owner, mutation);
            var snapshot = processing.snapshot(fixture.image().id(), fixture.image().wardrobeId());
            assertThat(snapshot.state()).isEqualTo(ProcessingStatus.READY_FOR_REVIEW);
            assertThat(snapshot.analysisStatus()).isEqualTo("FAILED");
            assertThat(snapshot.analysisFailure()).isEqualTo("INVALID_ANALYSIS_OUTPUT");
            String path = "/api/v1/garments/" + fixture.image().garmentId();
            mvc.perform(as(get(path), owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assets.cardUrl").isNotEmpty());
            mvc.perform(as(get(path + "/suggestions"), owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    @Test
    void invalidReviewCorrectionsRollBackTheDecisionAndPhotoState() throws Exception {
        String owner = "suggestion-invalid-correction";
        Fixture fixture = analysed(owner, null);
        String path = "/api/v1/garments/" + fixture.image().garmentId();
        JsonNode suggestion = response(as(get(path + "/suggestions"), owner)).get(0);
        ObjectNode request =
                json.createObjectNode()
                        .put("suggestionId", suggestion.path("id").asText())
                        .put("suggestionVersion", 0)
                        .put(
                                "garmentVersion",
                                response(as(get(path), owner)).path("version").asLong());
        for (ObjectNode invalid :
                new ObjectNode[] {
                    json.createObjectNode().put("name", ""),
                    json.createObjectNode().put("wearCount", 500),
                    json.createObjectNode().put("purchasePrice", -1)
                }) {
            request.set("corrections", invalid);
            mvc.perform(
                            as(post(path + "/suggestions/accept"), owner)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(request.toString()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(get(path + "/suggestions"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        assertThat(processing.snapshot(fixture.image().id(), fixture.image().wardrobeId()).state())
                .isEqualTo(ProcessingStatus.READY_FOR_REVIEW);
    }

    private Fixture analysed(String owner, String mutation) throws Exception {
        Fixture fixture = uploaded(owner);
        ProcessingResult media = verifiedResult(fixture.job());
        String key = fixture.job().outputPrefix() + "analysis.json";
        ObjectNode document = analysisDocument(fixture.job());
        ObjectNode fields = (ObjectNode) document.get("suggestions");
        if ("confidence".equals(mutation))
            ((ObjectNode) fields.get("materialEstimate")).put("confidence", 1.1);
        if ("category".equals(mutation))
            ((ObjectNode) fields.get("category")).put("value", "INVENTED");
        if ("extraField".equals(mutation))
            fields.set(
                    "purchasePrice",
                    json.createObjectNode().put("value", 500).put("confidence", 1));
        if ("missingField".equals(mutation)) fields.remove("length");
        if ("wrongImage".equals(mutation)) document.put("imageId", UUID.randomUUID().toString());
        if ("coercedValue".equals(mutation)) ((ObjectNode) fields.get("brand")).put("value", 5);
        when(storage.readJson(key)).thenReturn(document.toString());
        if ("wrongKey".equals(mutation)) key = "another-owner/analysis.json";
        var result =
                new ProcessingResult(
                        media.jobId(),
                        media.imageId(),
                        media.pipelineVersion(),
                        media.sourceChecksumSha256(),
                        media.assets(),
                        key,
                        null,
                        media.foregroundFraction(),
                        media.segmentationModel());
        results.accept(result, "analysis-result:" + fixture.job().jobId());
        results.accept(result, "analysis-result:" + fixture.job().jobId());
        return fixture;
    }

    private ObjectNode analysisDocument(WorkflowJob job) {
        ObjectNode root =
                json.createObjectNode()
                        .put("imageId", job.imageId().toString())
                        .put("pipelineVersion", job.pipelineVersion())
                        .put("modelId", "test-analysis-model")
                        .put("modelVersion", "2026-test")
                        .put("promptVersion", "garment-metadata-2");
        ObjectNode fields = json.createObjectNode();
        for (String name :
                new String[] {
                    "category",
                    "subcategory",
                    "primaryColour",
                    "secondaryColours",
                    "pattern",
                    "materialEstimate",
                    "length",
                    "formality",
                    "seasonTags",
                    "styleTags",
                    "occasionTags",
                    "brand",
                    "notes"
                }) {
            ObjectNode field = json.createObjectNode().put("confidence", .9);
            if (name.endsWith("Tags") || name.equals("secondaryColours"))
                field.set("value", json.createArrayNode());
            else field.putNull("value");
            fields.set(name, field);
        }
        ((ObjectNode) fields.get("category")).put("value", "TOP");
        ((ObjectNode) fields.get("primaryColour")).put("value", "Olive");
        ((ObjectNode) fields.get("materialEstimate"))
                .put("value", "Polyester")
                .put("confidence", .61);
        ((ObjectNode) fields.get("styleTags")).set("value", json.createArrayNode().add("Minimal"));
        root.set("suggestions", fields);
        return root;
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private Fixture uploaded(String subject) throws Exception {
        JsonNode upload = upload(subject, UUID.randomUUID(), body("image/png", 1024));
        UUID imageId = UUID.fromString(upload.path("imageId").asText());
        ImageRecord image =
                jdbc.sql(
                                "SELECT id, garment_id, wardrobe_id, user_id, source_s3_key, mime_type, expected_size, source_checksum, processing_status, assets::text, delete_original_after_isolation, original_filename, image_role FROM garment_image WHERE id = :id")
                        .param("id", imageId)
                        .query(ImageRecord.class)
                        .single();
        transitions.uploaded(image, "upload:" + image.id());
        UUID jobId =
                jdbc.sql("SELECT id FROM processing_job WHERE image_id = :id")
                        .param("id", image.id())
                        .query(UUID.class)
                        .single();
        return new Fixture(image, processing.context(jobId).orElseThrow());
    }

    private ProcessingResult verifiedResult(WorkflowJob job) {
        Map<String, AssetDescriptor> assets = new HashMap<>();
        for (String role : new String[] {"isolated", "display", "card", "thumbnail", "mask"}) {
            String key = job.outputPrefix() + role + (role.equals("mask") ? ".png" : ".webp");
            assets.put(role, new AssetDescriptor(key, CHECKSUM, 1024, 100, 200));
            when(storage.head(key))
                    .thenReturn(
                            Optional.of(
                                    new StoredObject(
                                            1024,
                                            role.equals("mask") ? "image/png" : "image/webp",
                                            CHECKSUM)));
        }
        return new ProcessingResult(
                job.jobId(),
                job.imageId(),
                job.pipelineVersion(),
                job.checksumSha256(),
                assets,
                null,
                "ANALYSIS_NOT_CONFIGURED",
                .5,
                "test-segmentation");
    }

    private int jobCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM processing_job WHERE image_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private int deletionCount(UUID id) {
        return jdbc.sql(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id = :id AND event_type = 'DELETE_ORIGINAL'")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private JsonNode upload(String owner, UUID key, String body) throws Exception {
        return json.readTree(
                mvc.perform(
                                as(post("/api/v1/garments/uploads"), owner)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private String body(String mime, long size) {
        return json.writeValueAsString(
                Map.of(
                        "filename",
                        "piece.png",
                        "mimeType",
                        mime,
                        "size",
                        size,
                        "checksumSha256",
                        CHECKSUM,
                        "imageRole",
                        "FRONT"));
    }

    private MockHttpServletRequestBuilder as(
            MockHttpServletRequestBuilder request, String subject) {
        return request.with(jwt().jwt(token -> token.subject(subject)));
    }

    private record Fixture(ImageRecord image, WorkflowJob job) {}
}
