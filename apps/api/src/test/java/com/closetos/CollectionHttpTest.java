package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class CollectionHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;

    @Test
    void manualMembershipPersistsAndDeletionCascadesWithoutDeletingGarments() throws Exception {
        String owner = "collection-manual";
        String top = garment(owner, "TOP", "White cotton shirt");
        String shoes = garment(owner, "SHOES", "Suede boots");
        var body = manual("Capsule", top, shoes);
        var collection = created(owner, body);
        String path = path(collection);
        assertThat(read(owner, path).path("garmentIds")).hasSize(2);
        assertThat(read(owner, path + "/garments").path("items")).hasSize(2);
        assertThat(read(owner, path + "/garments?category=TOP").path("items")).hasSize(1);
        send(owner, patch(path), manual("Small capsule", shoes).put("version", 0))
                .andExpect(status().isOk());
        assertThat(read(owner, path).path("version").asLong()).isEqualTo(1);
        assertThat(read(owner, path).path("garmentIds").get(0).asText()).isEqualTo(shoes);
        send(owner, patch(path), manual("Stale", top).put("version", 0))
                .andExpect(status().isConflict());
        mvc.perform(as(delete("/api/v1/garments/" + shoes).param("version", "0"), owner))
                .andExpect(status().isNoContent());
        assertThat(read(owner, path).path("garmentIds")).isEmpty();
        assertThat(read(owner, path + "/garments").path("items")).isEmpty();
        mvc.perform(as(delete(path).param("version", "0"), owner)).andExpect(status().isConflict());
        mvc.perform(as(delete(path).param("version", "1"), owner))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(path), owner)).andExpect(status().isNotFound());
        assertThat(read(owner, "/api/v1/garments/" + top).path("name").asText())
                .isEqualTo("White cotton shirt");
    }

    @Test
    void smartMembershipReflectsMetadataWearHistoryAndArchivedRules() throws Exception {
        String owner = "collection-smart";
        String top = garment(owner, "TOP", "Blue work shirt");
        String dress = garment(owner, "DRESS", "Evening dress");
        var rules = group("all", rule("category", "EQ", "TOP"), rule("wearCount", "EQ", 0));
        String path = path(created(owner, smart("Unworn tops", rules)));
        assertIds(read(owner, path + "/garments"), top);
        var wear = json.createObjectNode().put("wornOn", LocalDate.now().toString());
        wear.set("garmentIds", json.createArrayNode().add(top));
        var event =
                response(
                        send(
                                        owner,
                                        post("/api/v1/wear-events")
                                                .header("Idempotency-Key", UUID.randomUUID()),
                                        wear)
                                .andExpect(status().isCreated()));
        assertIds(read(owner, path + "/garments"));
        mvc.perform(as(delete("/api/v1/wear-events/" + event.path("id").asText()), owner))
                .andExpect(status().isNoContent());
        assertIds(read(owner, path + "/garments"), top);
        var detail = read(owner, "/api/v1/garments/" + top);
        send(
                        owner,
                        patch("/api/v1/garments/" + top),
                        json.createObjectNode()
                                .put("version", detail.path("version").asLong())
                                .put("category", "DRESS"))
                .andExpect(status().isOk());
        assertIds(read(owner, path + "/garments"));
        send(
                        owner,
                        post("/api/v1/garments/" + dress + "/archive"),
                        json.createObjectNode().put("version", 0))
                .andExpect(status().isOk());
        String archived = path(created(owner, smart("Archive", rule("status", "EQ", "ARCHIVED"))));
        assertIds(read(owner, archived + "/garments"), dress);
        String active = path(created(owner, smart("Dresses", rule("category", "EQ", "DRESS"))));
        assertIds(read(owner, active + "/garments"), top);
        assertThat(read(owner, path).path("garmentIds")).isEmpty();
    }

    @Test
    void nestedRulesUseTypedParametersForDatesTagsPricesAndLiteralText() throws Exception {
        String owner = "collection-dsl";
        String top = garment(owner, "TOP", "Formal linen shirt");
        String shoes = garment(owner, "SHOES", "Boots");
        String literal = "x' OR 1=1 --";
        String tagged = garment(owner, "TOP", literal);
        var update =
                json.createObjectNode()
                        .put("version", 0)
                        .put("primaryColourName", "Cream")
                        .put("brand", "Considered")
                        .put("purchasePrice", 80)
                        .put("purchaseCurrency", "GBP")
                        .put("purchaseDate", LocalDate.now().minusDays(60).toString());
        String season =
                switch (LocalDate.now().getMonthValue()) {
                    case 3, 4, 5 -> "Spring";
                    case 6, 7, 8 -> "Summer";
                    case 9, 10, 11 -> "Autumn";
                    default -> "Winter";
                };
        update.set("seasonTags", json.createArrayNode().add(season));
        update.set("occasionTags", json.createArrayNode().add("Work"));
        send(owner, patch("/api/v1/garments/" + top), update).andExpect(status().isOk());
        jdbc.sql("UPDATE garment SET created_at = now() - INTERVAL '200 days' WHERE id = :id")
                .param("id", UUID.fromString(top))
                .update();
        var query =
                group(
                        "all",
                        rule("ownedDays", "GT", 180),
                        rule("wearCount", "LT", 3),
                        ruleWithoutValue("season", "CONTAINS_CURRENT"),
                        rule("occasion", "CONTAINS", "work"),
                        rule("colour", "EQ", "CREAM"),
                        rule("purchasePrice", "GTE", 50),
                        rule("costPerWear", "LTE", 80),
                        rule("purchaseDate", "LT", LocalDate.now().toString()),
                        ruleWithoutValue("lastWornDate", "IS_NULL"),
                        group(
                                "any",
                                rule("brand", "EQ", "CONSIDERED"),
                                rule("name", "CONTAINS", "missing")),
                        json.createObjectNode().set("not", rule("category", "EQ", "SHOES")));
        assertIds(
                read(owner, path(created(owner, smart("Forgotten workwear", query))) + "/garments"),
                top);
        var in = ruleWithoutValue("category", "IN");
        in.set("value", json.createArrayNode().add("TOP").add("SHOES"));
        assertIds(
                read(owner, path(created(owner, smart("All pieces", in))) + "/garments"),
                top,
                shoes,
                tagged);
        assertIds(
                read(
                        owner,
                        path(created(owner, smart("Literal name", rule("name", "EQ", literal))))
                                + "/garments"),
                tagged);
        assertIds(
                read(
                        owner,
                        path(
                                        created(
                                                owner,
                                                smart(
                                                        "Literal contains",
                                                        rule("name", "CONTAINS", "OR 1=1"))))
                                + "/garments"),
                tagged);
        assertIds(
                read(
                        owner,
                        path(created(owner, smart("No size", ruleWithoutValue("size", "IS_NULL"))))
                                + "/garments?q=linen"),
                top);
        assertIds(
                read(
                        owner,
                        path(
                                        created(
                                                owner,
                                                smart(
                                                        "Exclude unknown brand",
                                                        json.createObjectNode()
                                                                .set(
                                                                        "not",
                                                                        rule(
                                                                                "brand", "EQ",
                                                                                "Nike")))))
                                + "/garments"),
                top,
                shoes,
                tagged);
        assertIds(
                read(
                        owner,
                        path(
                                        created(
                                                owner,
                                                smart(
                                                        "Other brands",
                                                        rule("brand", "NE", "Considered"))))
                                + "/garments"),
                shoes,
                tagged);
        var notIn = ruleWithoutValue("brand", "NOT_IN");
        notIn.set("value", json.createArrayNode().add("Considered"));
        assertIds(
                read(owner, path(created(owner, smart("No selected brands", notIn))) + "/garments"),
                shoes,
                tagged);
    }

    @Test
    void ownershipIsCheckedAtEveryCollectionAndMembershipBoundary() throws Exception {
        String owner = "collection-owner", stranger = "collection-stranger";
        String privateGarment = garment(owner, "TOP", "Private shirt");
        String ownGarment = garment(stranger, "SHOES", "Own boots");
        var collection = created(owner, manual("Private capsule", privateGarment));
        String path = path(collection);
        for (String suffix : List.of("", "/garments"))
            mvc.perform(as(get(path + suffix), stranger)).andExpect(status().isNotFound());
        send(stranger, patch(path), json.createObjectNode().put("version", 0).put("name", "Stolen"))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete(path).param("version", "0"), stranger))
                .andExpect(status().isNotFound());
        send(stranger, post("/api/v1/collections"), manual("Stolen", ownGarment, privateGarment))
                .andExpect(status().isNotFound());
        assertThat(read(stranger, "/api/v1/collections").path("items")).isEmpty();
        var ownCollection = created(stranger, manual("Own capsule", ownGarment));
        send(
                        stranger,
                        patch(path(ownCollection)),
                        manual("Mixed", ownGarment, privateGarment).put("version", 0))
                .andExpect(status().isNotFound());
        assertThat(read(stranger, path(ownCollection)).path("version").asLong()).isZero();
        UUID wardrobe =
                jdbc.sql("SELECT wardrobe_id FROM collection WHERE id = :id")
                        .param("id", UUID.fromString(collection.path("id").asText()))
                        .query(UUID.class)
                        .single();
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "INSERT INTO collection_garment(collection_id, garment_id, wardrobe_id) VALUES (:c, :g, :w)")
                                        .param("c", UUID.fromString(collection.path("id").asText()))
                                        .param("g", UUID.fromString(ownGarment))
                                        .param("w", wardrobe)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        var smart = created(owner, smart("Smart", rule("category", "EQ", "TOP")));
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "INSERT INTO collection_garment(collection_id, garment_id, wardrobe_id) VALUES (:c, :g, :w)")
                                        .param("c", UUID.fromString(smart.path("id").asText()))
                                        .param("g", UUID.fromString(privateGarment))
                                        .param("w", wardrobe)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(get("/api/v1/collections")).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedQueriesAndMembershipAreRejectedBeforePersistence() throws Exception {
        String owner = "collection-invalid";
        String garment = garment(owner, "TOP", "Shirt");
        List<ObjectNode> invalidRules =
                List.of(
                        rule("sql", "EQ", "DELETE FROM garment"),
                        rule("wearCount", "EQ", "0"),
                        rule("wearCount", "GT", -1),
                        rule("category", "EQ", "INVALID"),
                        rule("name", "GT", "shirt"),
                        rule("status", "CONTAINS", "AVAILABLE"),
                        rule("purchaseDate", "EQ", "2026-13-45"),
                        rule("purchasePrice", "EQ", "10.00"),
                        rule("season", "EQ", "Winter"),
                        rule("brand", "CONTAINS_CURRENT", "Winter"),
                        rule("season", "CONTAINS_CURRENT", "Winter"),
                        rule("name", "EQ", "x").put("sql", "TRUE"),
                        json.createObjectNode().set("all", json.createArrayNode()),
                        group("all", rule("name", "EQ", "shirt")).put("extra", true),
                        ruleWithoutValue("wearCount", "EQ"),
                        rule("name", "IS_NULL", "shirt"));
        for (var rules : invalidRules)
            send(owner, post("/api/v1/collections"), smart("Invalid", rules))
                    .andExpect(status().isBadRequest());
        ObjectNode nested = rule("wearCount", "EQ", 0);
        for (int i = 0; i < 10; i++) nested = group("all", nested);
        send(owner, post("/api/v1/collections"), smart("Too deep", nested))
                .andExpect(status().isBadRequest());
        var large = group("any", rule("wearCount", "EQ", 0));
        for (int i = 0; i < 50; i++)
            ((tools.jackson.databind.node.ArrayNode) large.get("any"))
                    .add(rule("wearCount", "EQ", i));
        send(owner, post("/api/v1/collections"), smart("Too wide", large))
                .andExpect(status().isBadRequest());
        for (var body :
                List.of(
                        manual("", garment),
                        manual("Duplicate", garment, garment),
                        manual("Unknown", garment).put("ownerId", "other"),
                        manual("Bad ID", "invalid"),
                        manual("Bad type", garment).put("type", "SQL"),
                        smart("Copied smart", rule("wearCount", "EQ", 0))
                                .set("garmentIds", json.createArrayNode().add(garment)),
                        manual("Manual with rules", garment)
                                .set("queryDefinition", rule("wearCount", "EQ", 0))))
            send(owner, post("/api/v1/collections"), body).andExpect(status().isBadRequest());
        assertThat(read(owner, "/api/v1/collections").path("items")).isEmpty();
    }

    @Test
    void collectionAndGarmentCursorsAreScopedToOwnerRulesVersionAndFilters() throws Exception {
        String owner = "collection-pagination";
        var ids = new ArrayList<String>();
        for (int i = 0; i < 7; i++) {
            ids.add(garment(owner, "TOP", "Capsule shirt " + i));
            created(owner, manual("Capsule " + i));
        }
        var collection = created(owner, manual("All capsule", ids.toArray(String[]::new)));
        String path = path(collection);
        for (String endpoint : List.of("/api/v1/collections", path + "/garments")) {
            Set<String> found = new HashSet<>();
            String cursor = null;
            do {
                var request = get(endpoint).param("limit", "3");
                if (cursor != null) request.param("cursor", cursor);
                var page = response(mvc.perform(as(request, owner)).andExpect(status().isOk()));
                for (var item : page.path("items"))
                    assertThat(found.add(item.path("id").asText())).isTrue();
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
                if (cursor != null) {
                    mvc.perform(
                                    as(
                                            get(endpoint)
                                                    .param("cursor", cursor)
                                                    .param("q", "changed"),
                                            owner))
                            .andExpect(status().isBadRequest());
                    if (endpoint.equals("/api/v1/collections"))
                        mvc.perform(
                                        as(
                                                get(endpoint).param("cursor", cursor),
                                                "other-collection-owner"))
                                .andExpect(status().isBadRequest());
                }
            } while (cursor != null);
            assertThat(found).hasSize(endpoint.equals("/api/v1/collections") ? 8 : 7);
        }
        var page = read(owner, path + "/garments?limit=2");
        String oldCursor = page.path("nextCursor").asText();
        send(owner, patch(path), json.createObjectNode().put("version", 0).put("name", "Renamed"))
                .andExpect(status().isOk());
        mvc.perform(as(get(path + "/garments").param("cursor", oldCursor), owner))
                .andExpect(status().isBadRequest());
        assertThat(read(owner, "/api/v1/collections?q=all&type=SMART").path("items")).isEmpty();
        mvc.perform(as(get("/api/v1/collections").param("limit", "101"), owner))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentEditsHaveOneWinnerAndManualCollectionsCanBecomeSavedQueries() throws Exception {
        String owner = "collection-concurrent";
        String garment = garment(owner, "TOP", "Shirt");
        String path = path(created(owner, manual("Original", garment)));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int index = i;
                statuses.add(
                        executor.submit(
                                () ->
                                        send(
                                                        owner,
                                                        patch(path),
                                                        json.createObjectNode()
                                                                .put("version", 0)
                                                                .put("name", "Edit " + index))
                                                .andReturn()
                                                .getResponse()
                                                .getStatus()));
            }
            List<Integer> completed = new ArrayList<>();
            for (var status : statuses) completed.add(status.get(20, TimeUnit.SECONDS));
            assertThat(completed).containsExactlyInAnyOrder(200, 409, 409, 409);
        }
        var toSmart = smart("Live tops", rule("category", "EQ", "TOP")).put("version", 1);
        toSmart.set("garmentIds", json.createArrayNode());
        send(owner, patch(path), toSmart).andExpect(status().isOk());
        assertIds(read(owner, path + "/garments"), garment);
        var toManual = manual("Chosen again", garment).put("version", 2);
        toManual.putNull("queryDefinition");
        send(owner, patch(path), toManual).andExpect(status().isOk());
        assertThat(read(owner, path).path("garmentIds")).hasSize(1);
    }

    @Test
    void builtInViewsCombineWithCollectionRulesAndKeepTheirDefaultSort() throws Exception {
        String owner = "collection-views";
        String worn = garment(owner, "TOP", "Work shirt");
        String unworn = garment(owner, "TOP", "Unworn shirt");
        String archived = garment(owner, "DRESS", "Archived dress");
        jdbc.sql(
                        """
                UPDATE garment SET wear_count_cached = 4, last_worn_at = CURRENT_DATE,
                    occasion_tags = '["Work", "Going Out"]', formality = 'Formal',
                    purchase_price = 80, purchase_currency = 'GBP' WHERE id = :id
                """)
                .param("id", UUID.fromString(worn))
                .update();
        jdbc.sql("UPDATE garment SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", UUID.fromString(archived))
                .update();
        for (String view :
                List.of(
                        "RECENTLY_WORN",
                        "MOST_WORN",
                        "WORK",
                        "GOING_OUT",
                        "FORMAL",
                        "BEST_COST_PER_WEAR",
                        "HIGHEST_COST_PER_WEAR"))
            assertIds(read(owner, "/api/v1/garments?view=" + view), worn);
        assertIds(read(owner, "/api/v1/garments?view=NEVER_WORN"), unworn);
        assertIds(read(owner, "/api/v1/garments?view=ARCHIVED"), archived);
        assertThat(
                        read(owner, "/api/v1/garments?view=LEAST_WORN")
                                .path("items")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo(unworn);
        var collection = created(owner, smart("Tops", rule("category", "EQ", "TOP")));
        assertIds(read(owner, path(collection) + "/garments?view=WORK"), worn);
        assertIds(read(owner, path(collection) + "/garments?view=ARCHIVED"));
        assertIds(read(owner, "/api/v1/garments?view=WORK&category=DRESS"));
        assertIds(
                read(
                        owner,
                        "/api/v1/garments?view=WORK&subcategory=&season=&occasion=&colour=&brand=&size=&formality=&tag=&q="),
                worn);
        assertIds(read(owner, "/api/v1/garments?view=PACKED"));
        jdbc.sql("UPDATE garment SET status = 'PACKED' WHERE id = :id")
                .param("id", UUID.fromString(unworn))
                .update();
        assertIds(read(owner, "/api/v1/garments?view=PACKED"), unworn);
        mvc.perform(as(get("/api/v1/garments?view=INVALID"), owner))
                .andExpect(status().isBadRequest());
        var groups = json.createArrayNode();
        for (int i = 0; i < 3; i++) {
            var children = json.createArrayNode();
            for (int j = 0; j < 32; j++) children.add(rule("category", "EQ", "TOP"));
            groups.add(json.createObjectNode().set("any", children));
        }
        var bounded =
                created(
                        owner,
                        smart("Maximum rule count", json.createObjectNode().set("all", groups)));
        assertIds(read(owner, path(bounded) + "/garments?view=WORK"), worn);
        groups.add(rule("wearCount", "EQ", 0));
        send(
                        owner,
                        post("/api/v1/collections"),
                        smart("Over maximum", json.createObjectNode().set("all", groups)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void smartPaginationCoversAThousandGarmentsAndManualMembershipUsesTheSameFilters()
            throws Exception {
        String owner = "collection-thousand";
        UUID wardrobe =
                UUID.fromString(read(owner, "/api/v1/wardrobes/current").path("id").asText());
        jdbc.sql(
                        """
                INSERT INTO garment(id, wardrobe_id, name, category, occasion_tags, created_at, updated_at)
                SELECT gen_random_uuid(), :wardrobe, 'Work linen shirt ' || n, 'TOP', '["Work"]', now(), now()
                FROM generate_series(1, 1000) n
                """)
                .param("wardrobe", wardrobe)
                .update();
        String path =
                path(created(owner, smart("Work edit", rule("occasion", "CONTAINS", "Work"))));
        Set<String> ids = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var request =
                    get(path + "/garments")
                            .param("limit", "73")
                            .param("sort", "NAME")
                            .param("q", "linen");
            if (cursor != null) request.param("cursor", cursor);
            var page = response(mvc.perform(as(request, owner)).andExpect(status().isOk()));
            for (var item : page.path("items"))
                assertThat(ids.add(item.path("id").asText())).isTrue();
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            assertThat(++pages).isLessThan(20);
        } while (cursor != null);
        assertThat(ids).hasSize(1000);
        var manual = created(owner, manual("Full wardrobe", ids.toArray(String[]::new)));
        assertThat(read(owner, path(manual)).path("garmentIds")).hasSize(1000);
        assertThat(read(owner, path(manual) + "/garments?view=WORK&limit=100").path("items"))
                .hasSize(100);
        assertIds(read(owner, path(manual) + "/garments?occasion=Dinner"));
    }

    private String garment(String owner, String category, String name) throws Exception {
        return response(
                        send(
                                        owner,
                                        post("/api/v1/garments"),
                                        json.createObjectNode()
                                                .put("name", name)
                                                .put("category", category))
                                .andExpect(status().isCreated()))
                .path("id")
                .asText();
    }

    private ObjectNode manual(String name, String... ids) {
        var body = json.createObjectNode().put("name", name).put("type", "MANUAL");
        var members = json.createArrayNode();
        for (String id : ids) members.add(id);
        body.set("garmentIds", members);
        return body;
    }

    private ObjectNode smart(String name, ObjectNode rules) {
        return json.createObjectNode()
                .put("name", name)
                .put("type", "SMART")
                .set("queryDefinition", rules);
    }

    private ObjectNode rule(String field, String operator, Object value) {
        return ruleWithoutValue(field, operator).set("value", json.valueToTree(value));
    }

    private ObjectNode ruleWithoutValue(String field, String operator) {
        return json.createObjectNode().put("field", field).put("operator", operator);
    }

    private ObjectNode group(String mode, ObjectNode... nodes) {
        var children = json.createArrayNode();
        for (var node : nodes) children.add(node);
        return json.createObjectNode().set(mode, children);
    }

    private JsonNode created(String owner, ObjectNode body) throws Exception {
        return response(
                send(owner, post("/api/v1/collections"), body).andExpect(status().isCreated()));
    }

    private String path(JsonNode collection) {
        return "/api/v1/collections/" + collection.path("id").asText();
    }

    private JsonNode read(String owner, String endpoint) throws Exception {
        return response(mvc.perform(as(get(endpoint), owner)).andExpect(status().isOk()));
    }

    private ResultActions send(String owner, MockHttpServletRequestBuilder request, ObjectNode body)
            throws Exception {
        return mvc.perform(
                as(request, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()));
    }

    private JsonNode response(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String owner) {
        return request.with(jwt().jwt(token -> token.subject(owner)));
    }

    private void assertIds(JsonNode page, String... ids) {
        List<String> actual = new ArrayList<>();
        for (var item : page.path("items")) actual.add(item.path("id").asText());
        assertThat(actual).containsExactlyInAnyOrder(ids);
    }
}
