package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class OutfitWearHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;

    @Test
    void canvasMetadataDuplicationArchiveAndDeletePersistWithVersionChecks() throws Exception {
        String owner = "outfit-lifecycle";
        var top = garment(owner, "TOP", 60);
        var shoes = garment(owner, "SHOES", 120);
        var created = outfit(owner, top, shoes);
        String path = outfitPath(created);
        var stored = read(owner, path);
        assertThat(stored.path("items")).isEqualTo(created.path("items"));
        assertThat(stored.path("items").get(0).path("x").decimalValue())
                .isEqualByComparingTo("24.75");
        assertThat(stored.path("items").get(1).path("rotation").decimalValue())
                .isEqualByComparingTo("-12.5");
        var update =
                json.createObjectNode()
                        .put("version", 0)
                        .put("name", "Evening layers")
                        .put("occasion", "Dinner")
                        .put("season", "Autumn")
                        .put("rating", 5)
                        .put("notes", "Wear with silver earrings");
        update.set("tags", json.createArrayNode().add("Favourite"));
        send(owner, patch(path), update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        send(owner, patch(path), update).andExpect(status().isConflict());
        var edited = read(owner, path);
        assertThat(edited.path("items")).isEqualTo(stored.path("items"));
        var copy =
                response(
                        send(
                                        owner,
                                        post(path + "/duplicate"),
                                        json.createObjectNode().put("version", 1))
                                .andExpect(status().isCreated()));
        assertThat(copy.path("name").asText()).isEqualTo("Evening layers (copy)");
        assertThat(copy.path("id")).isNotEqualTo(created.path("id"));
        assertThat(copy.path("items")).isEqualTo(edited.path("items"));
        send(owner, patch(path), json.createObjectNode().put("version", 1).put("archived", true))
                .andExpect(status().isOk());
        assertThat(read(owner, "/api/v1/outfits?archived=true").path("items")).hasSize(1);
        assertThat(read(owner, "/api/v1/outfits").path("items")).hasSize(1);
        mvc.perform(as(delete(path).param("version", "1"), owner)).andExpect(status().isConflict());
        mvc.perform(as(delete(path).param("version", "2"), owner))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(path), owner)).andExpect(status().isNotFound());
    }

    @Test
    void ownershipIsCheckedForOutfitsLayoutsAndEveryIncludedGarment() throws Exception {
        String owner = "outfit-owner", stranger = "outfit-intruder";
        var privateGarment = garment(owner, "TOP", 40);
        var privateOutfit = outfit(owner, privateGarment);
        String path = outfitPath(privateOutfit);
        mvc.perform(as(get(path), stranger)).andExpect(status().isNotFound());
        send(stranger, patch(path), json.createObjectNode().put("version", 0).put("name", "Stolen"))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete(path).param("version", "0"), stranger))
                .andExpect(status().isNotFound());
        send(stranger, post(path + "/duplicate"), json.createObjectNode().put("version", 0))
                .andExpect(status().isNotFound());
        send(stranger, post("/api/v1/outfits"), outfitBody(privateGarment))
                .andExpect(status().isNotFound());
        wear(stranger, path + "/wear", UUID.randomUUID(), wearBody(0, LocalDate.now()))
                .andExpect(status().isNotFound());
        var own = garment(stranger, "TOP", 30);
        var standalone = json.createObjectNode().put("wornOn", LocalDate.now().toString());
        standalone.set("garmentIds", json.createArrayNode().add(own).add(privateGarment));
        wear(stranger, "/api/v1/wear-events", UUID.randomUUID(), standalone)
                .andExpect(status().isNotFound());
        assertThat(read(stranger, "/api/v1/garments/" + own).path("wearCount").asInt()).isZero();
        assertThat(read(stranger, "/api/v1/wear-events").path("items")).isEmpty();
    }

    @Test
    void invalidGeometryAndDuplicateItemsAreRejectedWithoutRestrictingExperimentalSlots()
            throws Exception {
        String owner = "outfit-validation";
        var first = garment(owner, "DRESS", 80);
        var second = garment(owner, "DRESS", 80);
        outfit(owner, first, second);
        for (String field : List.of("x", "y", "scale", "rotation", "zIndex")) {
            var body = outfitBody(first);
            ((ObjectNode) body.path("items").get(0)).put(field, 2000);
            send(owner, post("/api/v1/outfits"), body).andExpect(status().isBadRequest());
        }
        send(owner, post("/api/v1/outfits"), outfitBody(first, first))
                .andExpect(status().isBadRequest());
        send(owner, post("/api/v1/outfits"), outfitBody(first).put("wearCount", 50))
                .andExpect(status().isBadRequest());
        send(owner, post("/api/v1/outfits"), outfitBody(first).put("name", " "))
                .andExpect(status().isBadRequest());
        var empty = outfit(owner);
        wear(owner, outfitPath(empty) + "/wear", UUID.randomUUID(), wearBody(0, LocalDate.now()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wearSnapshotsItemsAndReplaysTheOriginalReceiptAfterOutfitEdits() throws Exception {
        String owner = "wear-outfit";
        var top = garment(owner, "TOP", 60);
        var shoes = garment(owner, "SHOES", 100);
        String path = outfitPath(outfit(owner, top, shoes));
        UUID key = UUID.randomUUID();
        var body = wearBody(0, LocalDate.now().minusDays(1));
        var first = logged(owner, path + "/wear", key, body);
        assertThat(first.path("garmentIds")).hasSize(2);
        send(owner, patch(path), outfitBody(top).put("version", 0)).andExpect(status().isOk());
        var replay = logged(owner, path + "/wear", key, body);
        assertThat(replay.path("id")).isEqualTo(first.path("id"));
        assertThat(replay.path("garmentIds")).isEqualTo(first.path("garmentIds"));
        logged(owner, path + "/wear", UUID.randomUUID(), wearBody(1, LocalDate.now()));
        var detail = read(owner, "/api/v1/garments/" + top);
        assertThat(detail.path("wearCount").asInt()).isEqualTo(2);
        assertThat(detail.path("costPerWear").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(detail.path("lastWornAt").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(read(owner, "/api/v1/garments/" + shoes).path("wearCount").asInt()).isEqualTo(1);
        wear(owner, path + "/wear", key, body.deepCopy().put("notes", "Different entry"))
                .andExpect(status().isConflict());
        wear(owner, path + "/wear", UUID.randomUUID(), body).andExpect(status().isConflict());
        assertThat(read(owner, "/api/v1/wear-events?garmentId=" + shoes).path("items")).hasSize(1);
    }

    @Test
    void removedWearIsExcludedFromMetricsAndRepairRecalculatesFromHistory() throws Exception {
        String owner = "wear-repair";
        var garment = garment(owner, "TOP", 90);
        var body =
                json.createObjectNode()
                        .put("wornOn", LocalDate.now().minusDays(20).toString())
                        .put("context", "Work");
        body.set("garmentIds", json.createArrayNode().add(garment));
        var earlier = logged(owner, "/api/v1/wear-events", UUID.randomUUID(), body);
        UUID key = UUID.randomUUID();
        var laterBody = body.deepCopy().put("wornOn", LocalDate.now().toString());
        var later = logged(owner, "/api/v1/wear-events", key, laterBody);
        String path = "/api/v1/wear-events/" + later.path("id").asText();
        mvc.perform(as(get(path), "wear-stranger")).andExpect(status().isNotFound());
        mvc.perform(as(delete(path), "wear-stranger")).andExpect(status().isNotFound());
        mvc.perform(as(delete(path), owner)).andExpect(status().isNoContent());
        mvc.perform(as(get(path), owner)).andExpect(status().isNotFound());
        wear(owner, "/api/v1/wear-events", key, laterBody).andExpect(status().isConflict());
        jdbc.sql(
                        "UPDATE garment SET wear_count_cached = 999, last_worn_at = CURRENT_DATE WHERE id = :id")
                .param("id", UUID.fromString(garment))
                .update();
        mvc.perform(as(post("/api/v1/wear-events/recalculate"), owner))
                .andExpect(status().isNoContent());
        var repaired = read(owner, "/api/v1/garments/" + garment);
        assertThat(repaired.path("wearCount").asInt()).isEqualTo(1);
        assertThat(repaired.path("lastWornAt").asText()).isEqualTo(body.path("wornOn").asText());
        assertThat(repaired.path("costPerWear").decimalValue()).isEqualByComparingTo("90.00");
        mvc.perform(as(delete("/api/v1/wear-events/" + earlier.path("id").asText()), owner))
                .andExpect(status().isNoContent());
        var unworn = read(owner, "/api/v1/garments/" + garment);
        assertThat(unworn.path("wearCount").asInt()).isZero();
        assertThat(unworn.path("lastWornAt").isNull()).isTrue();
    }

    @Test
    void cursorsCoverEveryOutfitAndWearEntryWithoutDuplicatesOrCrossOwnerReuse() throws Exception {
        String owner = "outfit-pagination";
        var garment = garment(owner, "TOP", 40);
        for (int i = 0; i < 7; i++) {
            var outfit = outfit(owner, garment);
            logged(
                    owner,
                    outfitPath(outfit) + "/wear",
                    UUID.randomUUID(),
                    wearBody(0, LocalDate.now().minusDays(i)));
        }
        for (String path : List.of("/api/v1/outfits", "/api/v1/wear-events")) {
            Set<String> ids = new HashSet<>();
            String cursor = null;
            do {
                var request = as(get(path).param("limit", "3"), owner);
                if (cursor != null) request.param("cursor", cursor);
                var page = response(mvc.perform(request).andExpect(status().isOk()));
                for (var item : page.path("items"))
                    assertThat(ids.add(item.path("id").asText())).isTrue();
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
                if (cursor != null)
                    mvc.perform(as(get(path).param("cursor", cursor), "cursor-intruder"))
                            .andExpect(status().isBadRequest());
            } while (cursor != null);
            assertThat(ids).hasSize(7);
        }
    }

    @Test
    void concurrentRetriesAndIndependentWearEntriesKeepExactCounts() throws Exception {
        String owner = "wear-concurrent";
        var garment = garment(owner, "TOP", 60);
        String path = outfitPath(outfit(owner, garment)) + "/wear";
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonNode>> retries = new ArrayList<>();
            for (int i = 0; i < 5; i++)
                retries.add(
                        executor.submit(
                                () -> logged(owner, path, key, wearBody(0, LocalDate.now()))));
            var ids = new HashSet<String>();
            for (var retry : retries) ids.add(retry.get(20, TimeUnit.SECONDS).path("id").asText());
            assertThat(ids).hasSize(1);
            List<Future<JsonNode>> distinct = new ArrayList<>();
            for (int i = 0; i < 5; i++)
                distinct.add(
                        executor.submit(
                                () ->
                                        logged(
                                                owner,
                                                path,
                                                UUID.randomUUID(),
                                                wearBody(0, LocalDate.now()))));
            for (var entry : distinct) entry.get(20, TimeUnit.SECONDS);
        }
        assertThat(read(owner, "/api/v1/garments/" + garment).path("wearCount").asInt())
                .isEqualTo(6);
        assertThat(read(owner, "/api/v1/wear-events").path("items")).hasSize(6);
    }

    @Test
    void futureDatesMissingKeysAndUnauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/outfits")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wear-events")).andExpect(status().isUnauthorized());
        String owner = "wear-invalid";
        var garment = garment(owner, "TOP", 20);
        var body = json.createObjectNode().put("wornOn", LocalDate.now().plusDays(1).toString());
        body.set("garmentIds", json.createArrayNode().add(garment));
        wear(owner, "/api/v1/wear-events", UUID.randomUUID(), body)
                .andExpect(status().isBadRequest());
        body.put("wornOn", LocalDate.now().toString());
        send(owner, post("/api/v1/wear-events"), body).andExpect(status().isBadRequest());
        mvc.perform(
                        as(post("/api/v1/wear-events"), owner)
                                .header("Idempotency-Key", "invalid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.toString()))
                .andExpect(status().isBadRequest());
        assertThat(read(owner, "/api/v1/garments/" + garment).path("wearCount").asInt()).isZero();
    }

    @Test
    void deletingOutfitsAndGarmentsPreservesTheRemainingWearHistory() throws Exception {
        String owner = "wear-deleted-references";
        String top = garment(owner, "TOP", 40);
        String shoes = garment(owner, "SHOES", 60);
        var outfit = outfit(owner, top, shoes);
        var entry =
                logged(
                        owner,
                        outfitPath(outfit) + "/wear",
                        UUID.randomUUID(),
                        wearBody(0, LocalDate.now()));
        mvc.perform(as(delete(outfitPath(outfit)).param("version", "0"), owner))
                .andExpect(status().isNoContent());
        var preserved = read(owner, "/api/v1/wear-events/" + entry.path("id").asText());
        assertThat(preserved.path("outfitId").isNull()).isTrue();
        assertThat(preserved.path("outfitName").asText()).isEqualTo(outfit.path("name").asText());
        var topDetails = read(owner, "/api/v1/garments/" + top);
        mvc.perform(
                        as(
                                delete("/api/v1/garments/" + top)
                                        .param("version", topDetails.path("version").asText()),
                                owner))
                .andExpect(status().isNoContent());
        var history = read(owner, "/api/v1/wear-events/" + entry.path("id").asText());
        assertThat(history.path("garmentIds")).hasSize(1);
        assertThat(history.path("garmentIds").get(0).asText()).isEqualTo(shoes);
        assertThat(read(owner, "/api/v1/garments/" + shoes).path("wearCount").asInt()).isEqualTo(1);
        mvc.perform(as(delete("/api/v1/wear-events/" + entry.path("id").asText()), owner))
                .andExpect(status().isNoContent());
        assertThat(read(owner, "/api/v1/garments/" + shoes).path("wearCount").asInt()).isZero();
    }

    private String garment(String owner, String category, int price) throws Exception {
        var body =
                json.createObjectNode()
                        .put("name", "Considered " + category)
                        .put("category", category)
                        .put("purchasePrice", price)
                        .put("purchaseCurrency", "GBP");
        return response(send(owner, post("/api/v1/garments"), body).andExpect(status().isCreated()))
                .path("id")
                .asText();
    }

    private JsonNode outfit(String owner, String... garments) throws Exception {
        return response(
                send(owner, post("/api/v1/outfits"), outfitBody(garments))
                        .andExpect(status().isCreated()));
    }

    private String outfitPath(JsonNode outfit) {
        return "/api/v1/outfits/" + outfit.path("id").asText();
    }

    private ObjectNode outfitBody(String... garments) {
        var body = json.createObjectNode().put("name", "Considered layers");
        var items = json.createArrayNode();
        for (int i = 0; i < garments.length; i++)
            items.add(
                    json.createObjectNode()
                            .put("garmentId", garments[i])
                            .put("x", 24.75 + i * 10)
                            .put("y", 50)
                            .put("scale", 1.25)
                            .put("rotation", i == 0 ? 7.5 : -12.5)
                            .put("zIndex", i));
        body.set("items", items);
        return body;
    }

    private ObjectNode wearBody(long version, LocalDate date) {
        return json.createObjectNode().put("version", version).put("wornOn", date.toString());
    }

    private org.springframework.test.web.servlet.ResultActions wear(
            String owner, String path, UUID key, ObjectNode body) throws Exception {
        return send(owner, post(path).header("Idempotency-Key", key), body);
    }

    private JsonNode logged(String owner, String path, UUID key, ObjectNode body) throws Exception {
        return response(wear(owner, path, key, body).andExpect(status().isCreated()));
    }

    private JsonNode read(String owner, String path) throws Exception {
        return response(mvc.perform(as(get(path), owner)).andExpect(status().isOk()));
    }

    private org.springframework.test.web.servlet.ResultActions send(
            String owner, MockHttpServletRequestBuilder request, ObjectNode body) throws Exception {
        return mvc.perform(
                as(request, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()));
    }

    private JsonNode response(org.springframework.test.web.servlet.ResultActions result)
            throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String owner) {
        return request.with(jwt().jwt(token -> token.subject(owner)));
    }
}
