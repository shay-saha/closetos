package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.closetos.packing.api.PackingSolution;
import com.closetos.packing.api.PackingSolverPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
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
class PackingHttpTest extends PostgresIntegrationTest {
    private static final String ROOT = "/api/v1/packing-lists";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoBean PackingSolverPort solver;

    @Test
    void authenticationOwnershipNestedValidationAndOwnerForeignKeysAreEnforced() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(as(get(ROOT).param("limit", "101"), "packing-validation"))
                .andExpect(status().isBadRequest());
        var owner = "packing-ownership";
        var list = create(owner, request("Owner trip"));
        String path = ROOT + "/" + list.path("id").asText();
        for (var foreignRequest :
                List.of(
                        get(path),
                        patch(path).content("{\"version\":0,\"name\":\"Changed\"}"),
                        delete(path).param("version", "0"),
                        post(path + "/optimise").content("{\"version\":0}")))
            mvc.perform(as(foreignRequest, "packing-foreign")).andExpect(status().isNotFound());
        UUID foreign = piece("packing-foreign", "Private dress", "DRESS");
        var constrained = request("Foreign piece");
        ((ObjectNode) constrained.path("constraints"))
                .putArray("requiredGarments")
                .add(foreign.toString());
        mvc.perform(as(post(ROOT).content(constrained.toString()), owner))
                .andExpect(status().isNotFound());
        var unknown = request("Unknown field");
        ((ObjectNode) unknown.path("constraints").path("weather"))
                .put("wardrobeId", UUID.randomUUID().toString());
        mvc.perform(as(post(ROOT).content(unknown.toString()), owner))
                .andExpect(status().isBadRequest());
        var invalid = request("Invalid schedule");
        ((ObjectNode) invalid.path("constraints").path("occasions").get(0)).put("days", 2);
        mvc.perform(as(post(ROOT).content(invalid.toString()), owner))
                .andExpect(status().isBadRequest());
        UUID wardrobe =
                jdbc.sql("SELECT wardrobe_id FROM packing_list WHERE id = :id")
                        .param("id", UUID.fromString(list.path("id").asText()))
                        .query(UUID.class)
                        .single();
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "INSERT INTO packing_item(packing_list_id, wardrobe_id, garment_id) VALUES (:list, :wardrobe, :garment)")
                                        .param("list", UUID.fromString(list.path("id").asText()))
                                        .param("wardrobe", wardrobe)
                                        .param("garment", foreign)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(solver);
    }

    @Test
    void draftCrudUsesScopedPaginationAndRejectsStaleWrites() throws Exception {
        String owner = "packing-crud";
        var first = create(owner, request("First trip"));
        var second = create(owner, request("Second trip"));
        create("packing-crud-foreign", request("Private trip"));
        var page = read(owner, get(ROOT).param("limit", "1"));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("nextCursor").asText()).isNotBlank();
        var next =
                read(
                        owner,
                        get(ROOT)
                                .param("limit", "1")
                                .param("cursor", page.path("nextCursor").asText()));
        assertThat(next.path("items")).hasSize(1);
        assertThat(next.path("items").get(0).path("id").asText())
                .isNotEqualTo(page.path("items").get(0).path("id").asText());
        mvc.perform(
                        as(
                                get(ROOT).param("cursor", page.path("nextCursor").asText()),
                                "packing-crud-foreign"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        as(
                                get(ROOT)
                                        .param("cursor", page.path("nextCursor").asText())
                                        .param("q", "First"),
                                owner))
                .andExpect(status().isBadRequest());
        String path = ROOT + "/" + first.path("id").asText();
        var updated = read(owner, patch(path).content("{\"version\":0,\"name\":\"Renamed trip\"}"));
        assertThat(updated.path("name").asText()).isEqualTo("Renamed trip");
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        mvc.perform(as(patch(path).content("{\"version\":0,\"name\":\"Lost edit\"}"), owner))
                .andExpect(status().isConflict());
        mvc.perform(as(delete(path).param("version", "1"), owner))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(path), owner)).andExpect(status().isNotFound());
        assertThat(read(owner, get(ROOT)).path("items")).hasSize(1);
        assertThat(read(owner, get(ROOT)).path("items").get(0).path("id").asText())
                .isEqualTo(second.path("id").asText());
    }

    @Test
    void validatedCapsulesPackAndUnpackTheActualGarmentAndProtectPackedLists() throws Exception {
        String owner = "packing-packed";
        UUID dress = piece(owner, "Trip dress", "DRESS");
        UUID shoes = piece(owner, "Trip shoes", "SHOES");
        when(solver.solve(any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return capsule(dress, shoes);
                        });
        var draft = create(owner, request("Packed trip"));
        String path = ROOT + "/" + draft.path("id").asText();
        var planned = read(owner, post(path + "/optimise").content("{\"version\":0}"));
        assertThat(planned.path("plan").path("status").asText()).isEqualTo("OPTIMAL");
        assertThat(planned.path("items")).hasSize(2);
        assertThat(planned.path("stale").asBoolean()).isFalse();
        assertThat(planned.path("explanations").toString()).contains("Selected 2 unique pieces");
        var packed =
                read(
                        owner,
                        patch(path + "/items/" + dress).content(itemRequest(planned, dress, true)));
        assertThat(read(owner, get("/api/v1/garments/" + dress)).path("status").asText())
                .isEqualTo("PACKED");
        assertThat(packed.path("stale").asBoolean()).isFalse();
        mvc.perform(as(delete(path).param("version", packed.path("version").asText()), owner))
                .andExpect(status().isConflict());
        mvc.perform(
                        as(
                                post(path + "/optimise")
                                        .content(
                                                "{\"version\":"
                                                        + packed.path("version").asLong()
                                                        + "}"),
                                owner))
                .andExpect(status().isConflict());
        var unpacked =
                read(
                        owner,
                        patch(path + "/items/" + dress).content(itemRequest(packed, dress, false)));
        assertThat(read(owner, get("/api/v1/garments/" + dress)).path("status").asText())
                .isEqualTo("AVAILABLE");
        assertThat(unpacked.path("stale").asBoolean()).isFalse();
        mvc.perform(as(delete(path).param("version", unpacked.path("version").asText()), owner))
                .andExpect(status().isNoContent());
    }

    @Test
    void manualOverridesEnforceConstraintsAndConstraintEditsClearTheOldCapsule() throws Exception {
        String owner = "packing-manual";
        UUID dress = piece(owner, "Required dress", "DRESS");
        UUID shoes = piece(owner, "Required shoes", "SHOES");
        var input = request("Manual trip");
        ((ObjectNode) input.path("constraints")).putArray("requiredGarments").add(dress.toString());
        var draft = create(owner, input);
        String path = ROOT + "/" + draft.path("id").asText();
        var invalid = json.createObjectNode().put("version", 0);
        invalid.set(
                "plan",
                json.valueToTree(
                        new PackingSolution(
                                PackingSolution.Status.OPTIMAL,
                                List.of(shoes),
                                List.of(new PackingSolution.ScheduledOutfit(0, List.of(shoes))),
                                List.of())));
        mvc.perform(as(post(path + "/manual").content(invalid.toString()), owner))
                .andExpect(status().isBadRequest());
        var manual = json.createObjectNode().put("version", 0);
        manual.set("plan", json.valueToTree(capsule(dress, shoes)));
        var saved = read(owner, post(path + "/manual").content(manual.toString()));
        assertThat(saved.path("manualOverride").asBoolean()).isTrue();
        assertThat(saved.path("plan").path("status").asText()).isEqualTo("FEASIBLE");
        assertThat(saved.path("explanations").toString()).contains("Optimality is not claimed");
        var rename = read(owner, patch(path).content("{\"version\":1,\"name\":\"Same capsule\"}"));
        assertThat(rename.path("items")).hasSize(2);
        var changed = json.createObjectNode().put("version", 2);
        var constraints = (ObjectNode) input.path("constraints").deepCopy();
        constraints.put("maximumGarments", 1);
        changed.set("constraints", constraints);
        var cleared = read(owner, patch(path).content(changed.toString()));
        assertThat(cleared.path("plan").isNull()).isTrue();
        assertThat(cleared.path("items")).isEmpty();
        assertThat(cleared.path("explanations")).isEmpty();
        verifyNoInteractions(solver);
    }

    @Test
    void concurrentWardrobeEditsInvalidateTheSolverSnapshotBeforePersistence() throws Exception {
        String owner = "packing-concurrent";
        UUID dress = piece(owner, "Original dress", "DRESS");
        UUID shoes = piece(owner, "Original shoes", "SHOES");
        var draft = create(owner, request("Concurrent trip"));
        String path = ROOT + "/" + draft.path("id").asText();
        when(solver.solve(any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            try (var concurrentRequests =
                                    java.util.concurrent.Executors
                                            .newVirtualThreadPerTaskExecutor()) {
                                concurrentRequests
                                        .submit(
                                                () ->
                                                        mvc.perform(
                                                                        as(
                                                                                patch(
                                                                                                "/api/v1/garments/"
                                                                                                        + dress)
                                                                                        .content(
                                                                                                "{\"version\":0,\"name\":\"Changed during solve\"}"),
                                                                                owner))
                                                                .andExpect(status().isOk()))
                                        .get();
                            }
                            return capsule(dress, shoes);
                        });
        mvc.perform(as(post(path + "/optimise").content("{\"version\":0}"), owner))
                .andExpect(status().isConflict());
        var unchanged = read(owner, get(path));
        assertThat(unchanged.path("version").asLong()).isZero();
        assertThat(unchanged.path("plan").isNull()).isTrue();
        assertThat(unchanged.path("items")).isEmpty();
    }

    @Test
    void structuralInfeasibilityReturnsAnExplanationAndNoFakeCapsule() throws Exception {
        String owner = "packing-infeasible";
        var draft = create(owner, request("Empty wardrobe"));
        var result =
                read(
                        owner,
                        post(ROOT + "/" + draft.path("id").asText() + "/optimise")
                                .content("{\"version\":0}"));
        assertThat(result.path("plan").path("status").asText()).isEqualTo("INFEASIBLE");
        assertThat(result.path("plan").path("selectedGarments")).isEmpty();
        assertThat(result.path("plan").path("outfits")).isEmpty();
        assertThat(result.path("plan").path("warnings").toString())
                .contains("MISSING_OUTFIT_STRUCTURE", "complete outfit");
        verifyNoInteractions(solver);
    }

    private ObjectNode request(String name) {
        var result =
                json.createObjectNode()
                        .put("name", name)
                        .put("startDate", "2026-07-01")
                        .put("endDate", "2026-07-01");
        var constraints =
                result.putObject("constraints")
                        .put("maximumGarments", 5)
                        .put("laundryEveryDays", 0)
                        .put("maximumWearsBetweenLaundry", 1);
        constraints.putObject("weather").putArray("assumptions").add("MILD");
        constraints.putArray("occasions").addObject().put("name", "Everyday").put("days", 1);
        return result;
    }

    private PackingSolution capsule(UUID dress, UUID shoes) {
        return new PackingSolution(
                PackingSolution.Status.OPTIMAL,
                List.of(dress, shoes),
                List.of(new PackingSolution.ScheduledOutfit(0, List.of(dress, shoes))),
                List.of());
    }

    private UUID piece(String owner, String name, String category) throws Exception {
        var body = json.createObjectNode().put("name", name).put("category", category);
        body.putArray("styleTags").add("mild");
        var result =
                mvc.perform(as(post("/api/v1/garments").content(body.toString()), owner))
                        .andExpect(status().isCreated())
                        .andReturn();
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private JsonNode create(String owner, ObjectNode body) throws Exception {
        var result =
                mvc.perform(as(post(ROOT).content(body.toString()), owner))
                        .andExpect(status().isCreated())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode read(String owner, MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(as(request, owner)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String owner) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .with(
                        jwt().jwt(
                                        token ->
                                                token.subject(owner)
                                                        .issuer("https://issuer.example.test")
                                                        .audience(List.of("closetos-test"))));
    }

    private String itemRequest(JsonNode list, UUID garment, boolean packed) {
        for (var item : list.path("items"))
            if (item.path("garment").path("id").asText().equals(garment.toString()))
                return json.createObjectNode()
                        .put("version", list.path("version").asLong())
                        .put("garmentVersion", item.path("garment").path("version").asLong())
                        .put("packed", packed)
                        .toString();
        throw new AssertionError("Packing item was not returned");
    }
}
