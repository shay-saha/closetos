package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.media.api.PersonalPhotoDownloads;
import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@Import(PersonalDataHttpTest.GateConfiguration.class)
class PersonalDataHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired SnapshotGate gate;
    @MockitoBean PersonalPhotoDownloads photos;

    @BeforeEach
    void photoLinks() {
        when(photos.sign(any(), any(), any(), any(), any()))
                .thenAnswer(
                        call ->
                                "https://private.example.test/"
                                        + call.getArgument(0)
                                        + "/"
                                        + call.getArgument(2)
                                        + "?expires="
                                        + call.getArgument(4));
    }

    @Test
    void exportsAllSavedSectionsOnlyForTheAuthenticatedOwner() throws Exception {
        var owner = account();
        var other = account();
        var piece = seed(owner, "My shirt", false);
        seed(other, "Other person's private shirt", false);
        var response =
                mvc.perform(
                                get("/api/v1/me/data")
                                        .param("wardrobeId", other.wardrobe().toString())
                                        .with(jwt().jwt(token -> token.subject(owner.subject()))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Content-Disposition"))
                .matches("attachment; filename=\"closetos-data-[0-9-]+\\.json\"");
        JsonNode data = json.readTree(response.getContentAsByteArray());
        assertThat(data.path("complete").asBoolean()).isTrue();
        assertThat(data.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(Instant.parse(data.path("photoLinksExpireAt").asText()))
                .isEqualTo(Instant.parse(data.path("exportedAt").asText()).plusSeconds(900));
        for (String section :
                new String[] {
                    "profile",
                    "wardrobes",
                    "garments",
                    "suggestions",
                    "images",
                    "processingHistory",
                    "outfits",
                    "outfitItems",
                    "wearEvents",
                    "wearEventGarments",
                    "collections",
                    "collectionGarments",
                    "packingLists",
                    "packingItems",
                    "embeddings",
                    "embeddingModels",
                    "embeddingStatus"
                }) assertThat(data.path(section).size()).as(section).isEqualTo(1);
        assertThat(data.path("garments").get(0).path("id").asText()).isEqualTo(piece.toString());
        assertThat(data.path("garments").get(0).path("notes").asText())
                .isEqualTo("Private notes: \"hello\"\n</script>");
        assertThat(data.path("embeddings").get(0).path("embedding").size()).isEqualTo(256);
        assertThat(data.path("embeddings").get(0).path("embedding").get(0).asDouble()).isEqualTo(1);
        assertThat(data.path("images").get(0).path("files").size()).isEqualTo(2);
        assertThat(response.getContentAsString())
                .doesNotContain(
                        other.user().toString(),
                        other.wardrobe().toString(),
                        "Other person's",
                        "source_s3_key",
                        "request_fingerprint",
                        "lease_owner",
                        "search_document");
    }

    @Test
    void omitsDeletedOriginalsAndDoesNotReuseAnEarlierSnapshot() throws Exception {
        var owner = account();
        seed(owner, "Reviewed piece", true);
        JsonNode first = export(owner);
        assertThat(first.path("images").get(0).path("files").size()).isEqualTo(1);
        assertThat(first.path("images").get(0).path("files").get(0).path("role").asText())
                .isEqualTo("isolated");
        jdbc.update(
                "UPDATE garment SET name='Corrected name' WHERE wardrobe_id=?", owner.wardrobe());
        assertThat(export(owner).path("garments").get(0).path("name").asText())
                .isEqualTo("Corrected name");
        mvc.perform(get("/api/v1/me/data").with(jwt().jwt(token -> token.subject(owner.subject()))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void exportsBeyondCataloguePagesWithoutLosingArchivedOrPendingPieces() throws Exception {
        var owner = account();
        jdbc.update(
                """
                INSERT INTO garment(id,wardrobe_id,name,category,status,processing_status,created_at,updated_at)
                SELECT gen_random_uuid(), ?, 'Owned piece ' || n, 'TOP',
                    CASE WHEN n%2=0 THEN 'ARCHIVED' ELSE 'AVAILABLE' END,
                    CASE WHEN n%3=0 THEN 'AWAITING_UPLOAD' ELSE 'READY' END, now(), now()
                FROM generate_series(1,513) n
                """,
                owner.wardrobe());
        assertThat(export(owner).path("garments").size()).isEqualTo(513);
    }

    @Test
    void maintainsOneSnapshotDuringEditsAndRejectsConcurrentDownloads() throws Exception {
        var owner = account();
        seed(owner, "Before edit", false);
        var block = new Block(owner.user(), new CountDownLatch(1), new CountDownLatch(1));
        gate.block = block;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var downloading = executor.submit(() -> export(owner));
            try {
                assertThat(block.entered().await(10, TimeUnit.SECONDS)).isTrue();
                jdbc.update(
                        "UPDATE garment SET name='After edit' WHERE wardrobe_id=?",
                        owner.wardrobe());
                mvc.perform(
                                get("/api/v1/me/data")
                                        .with(jwt().jwt(token -> token.subject(owner.subject()))))
                        .andExpect(status().isTooManyRequests());
            } finally {
                block.release().countDown();
                gate.block = null;
            }
            assertThat(
                            downloading
                                    .get(15, TimeUnit.SECONDS)
                                    .path("garments")
                                    .get(0)
                                    .path("name")
                                    .asText())
                    .isEqualTo("Before edit");
        }
        assertThat(export(owner).path("garments").get(0).path("name").asText())
                .isEqualTo("After edit");
    }

    @Test
    void rejectsUnauthenticatedDataRequests() throws Exception {
        mvc.perform(get("/api/v1/me/data")).andExpect(status().isUnauthorized());
    }

    @Test
    void includesModelIdentityForFailedIndexingWorkWithoutAnEmbedding() throws Exception {
        var owner = account();
        seed(owner, "Indexing unavailable", false);
        jdbc.update("DELETE FROM garment_embedding WHERE wardrobe_id=?", owner.wardrobe());
        jdbc.update(
                "UPDATE garment_embedding_work SET state='FAILED',failure_code='INFERENCE_UNAVAILABLE' WHERE wardrobe_id=?",
                owner.wardrobe());
        JsonNode data = export(owner);
        assertThat(data.path("embeddings").size()).isZero();
        assertThat(data.path("embeddingStatus").get(0).path("state").asText()).isEqualTo("FAILED");
        assertThat(data.path("embeddingModels").size()).isEqualTo(1);
        assertThat(data.path("embeddingModels").get(0).path("model_key").asText())
                .isEqualTo(data.path("embeddingStatus").get(0).path("model_key").asText());
    }

    private JsonNode export(Account owner) throws Exception {
        return json.readTree(
                mvc.perform(
                                get("/api/v1/me/data")
                                        .with(jwt().jwt(token -> token.subject(owner.subject()))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray());
    }

    private Account account() throws Exception {
        String subject = "privacy-" + UUID.randomUUID();
        JsonNode wardrobe =
                json.readTree(
                        mvc.perform(
                                        get("/api/v1/wardrobes/current")
                                                .with(jwt().jwt(token -> token.subject(subject))))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        UUID user =
                jdbc.queryForObject(
                        "SELECT id FROM user_profile WHERE cognito_sub=?", UUID.class, subject);
        return new Account(subject, user, UUID.fromString(wardrobe.path("id").asText()));
    }

    private UUID seed(Account owner, String name, boolean originalDeleted) {
        UUID piece = UUID.randomUUID(),
                image = UUID.randomUUID(),
                job = UUID.randomUUID(),
                outfit = UUID.randomUUID(),
                wear = UUID.randomUUID(),
                collection = UUID.randomUUID(),
                packing = UUID.randomUUID();
        String prefix = "users/" + owner.user() + "/garments/" + piece + "/images/" + image + "/";
        String checksum = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        jdbc.update(
                "INSERT INTO garment(id,wardrobe_id,name,category,notes,created_at,updated_at) VALUES (?, ?, ?, 'TOP', ?, now(),now())",
                piece,
                owner.wardrobe(),
                name,
                "Private notes: \"hello\"\n</script>");
        jdbc.update(
                """
                INSERT INTO garment_image(id,garment_id,wardrobe_id,user_id,image_role,original_filename,mime_type,source_s3_key,expected_size,source_checksum,upload_key,processing_status,original_deleted,assets)
                VALUES (?, ?, ?, ?, 'FRONT', 'shirt.png', 'image/png', ?, 1024, ?, ?, 'READY', ?, jsonb_build_object('isolated', jsonb_build_object('key',?::text,'checksumSha256',?::text,'size',100,'width',100,'height',200)))
                """,
                image,
                piece,
                owner.wardrobe(),
                owner.user(),
                prefix + "original.png",
                checksum,
                UUID.randomUUID(),
                originalDeleted,
                prefix + "pipelines/1-r1/isolated.webp",
                checksum);
        jdbc.update(
                "INSERT INTO processing_job(id,image_id,pipeline_version,state,attempt_count) VALUES (?,?,'1-r1','READY',1)",
                job,
                image);
        jdbc.update(
                "INSERT INTO garment_ai_suggestion(id,garment_id,image_id,processing_job_id,wardrobe_id,model_id,prompt_version,pipeline_version,suggestion_json) VALUES (?,?,?,?,?,'test-model','1','1-r1','{\"category\":{\"value\":\"TOP\",\"confidence\":0.9}}')",
                UUID.randomUUID(),
                piece,
                image,
                job,
                owner.wardrobe());
        jdbc.update(
                "INSERT INTO outfit(id,wardrobe_id,name,created_at,updated_at) VALUES (?,?,'Saved outfit',now(),now())",
                outfit,
                owner.wardrobe());
        jdbc.update(
                "INSERT INTO outfit_item(outfit_id,garment_id,x,y,scale,rotation,z_index) VALUES (?,?,1,2,1,0,0)",
                outfit,
                piece);
        jdbc.update(
                "INSERT INTO wear_event(id,user_id,wardrobe_id,outfit_id,worn_on,idempotency_key,request_fingerprint) VALUES (?,?,?,?,current_date,?,?)",
                wear,
                owner.user(),
                owner.wardrobe(),
                outfit,
                UUID.randomUUID(),
                "a".repeat(64));
        jdbc.update(
                "INSERT INTO wear_event_garment(wear_event_id,garment_id) VALUES (?,?)",
                wear,
                piece);
        jdbc.update(
                "INSERT INTO collection(id,wardrobe_id,name,type,created_at,updated_at) VALUES (?,?,'Saved collection','MANUAL',now(),now())",
                collection,
                owner.wardrobe());
        jdbc.update(
                "INSERT INTO collection_garment(collection_id,garment_id,wardrobe_id) VALUES (?,?,?)",
                collection,
                piece,
                owner.wardrobe());
        jdbc.update(
                "INSERT INTO packing_list(id,wardrobe_id,name,start_date,end_date,constraints,created_at,updated_at) VALUES (?,?,'Saved trip',current_date,current_date+1,'{\"maximumGarments\":4}',now(),now())",
                packing,
                owner.wardrobe());
        jdbc.update(
                "INSERT INTO packing_item(packing_list_id,wardrobe_id,garment_id) VALUES (?,?,?)",
                packing,
                owner.wardrobe(),
                piece);
        String model = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update(
                "INSERT INTO embedding_model(model_key,provider,model_id,pipeline_version,dimensions) VALUES (?,'local','test-model','1',256)",
                model);
        String vector = "[1" + ",0".repeat(255) + "]";
        jdbc.update(
                "INSERT INTO garment_embedding(garment_id,wardrobe_id,model_key,dimensions,embedding,source_fingerprint,updated_at) VALUES (?,?,?,256,?::vector,?,now())",
                piece,
                owner.wardrobe(),
                model,
                vector,
                "b".repeat(64));
        jdbc.update(
                "INSERT INTO garment_embedding_work(garment_id,wardrobe_id,model_key,source_fingerprint,state,updated_at) VALUES (?,?,?,?,'READY',now())",
                piece,
                owner.wardrobe(),
                model,
                "b".repeat(64));
        return piece;
    }

    record Account(String subject, UUID user, UUID wardrobe) {}

    record Block(UUID owner, CountDownLatch entered, CountDownLatch release) {}

    static class SnapshotGate implements PersonalDataContributor {
        volatile Block block;

        @Override
        public void write(PersonalDataWriter writer, UUID owner, UUID wardrobe, Instant expiry) {
            var waiting = block;
            if (waiting == null || !waiting.owner().equals(owner)) return;
            waiting.entered().countDown();
            try {
                if (!waiting.release().await(20, TimeUnit.SECONDS))
                    throw new IllegalStateException("Snapshot test timed out.");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfiguration {
        @Bean
        SnapshotGate snapshotGate() {
            return new SnapshotGate();
        }
    }
}
