package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.closetos.garment.api.GarmentSort;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class GarmentHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;

    @Test
    void createPatchArchiveRestoreAndDeletePersistRealData() throws Exception {
        JsonNode created =
                create(
                        "garment-lifecycle",
                        "{\"name\":\"Cream knit\",\"category\":\"TOP\",\"purchasePrice\":45,\"purchaseCurrency\":\"GBP\"}");
        String path = "/api/v1/garments/" + created.get("id").asText();
        assertThat(created.get("wearCount").asInt()).isZero();
        assertThat(created.get("costPerWear").decimalValue()).isEqualByComparingTo("45.00");
        mvc.perform(
                        as(patch(path), "garment-lifecycle")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"version\":0,\"name\":\"Favourite knit\",\"brand\":\"Arket\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Favourite knit"))
                .andExpect(jsonPath("$.category").value("TOP"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(
                        as(patch(path), "garment-lifecycle")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"name\":\"Outdated\"}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        as(post(path + "/archive"), "garment-lifecycle")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(as(get("/api/v1/garments"), "garment-lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(
                        as(post(path + "/restore"), "garment-lifecycle")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(as(delete(path).param("version", "3"), "garment-lifecycle"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(path), "garment-lifecycle")).andExpect(status().isNotFound());
    }

    @Test
    void guessedIdsCannotReadModifyArchiveOrDeleteAnotherOwnersGarment() throws Exception {
        JsonNode garment =
                create("garment-owner", "{\"name\":\"Private dress\",\"category\":\"DRESS\"}");
        String path = "/api/v1/garments/" + garment.get("id").asText();
        mvc.perform(as(get(path), "garment-intruder")).andExpect(status().isNotFound());
        mvc.perform(
                        as(patch(path), "garment-intruder")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"name\":\"Stolen\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        as(post(path + "/archive"), "garment-intruder")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete(path).param("version", "0"), "garment-intruder"))
                .andExpect(status().isNotFound());
        mvc.perform(as(get(path), "garment-owner")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/garments"), "garment-intruder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void invalidMetadataAndReadOnlyPatchFieldsAreRejected() throws Exception {
        for (String body :
                new String[] {
                    "{\"name\":\" \",\"category\":\"TOP\"}",
                    "{\"name\":\"Knit\",\"category\":\"INVALID\"}",
                    "{\"name\":\"Knit\",\"category\":\"TOP\",\"purchasePrice\":-1,\"purchaseCurrency\":\"GBP\"}",
                    "{\"name\":\"Knit\",\"category\":\"TOP\",\"purchasePrice\":5}",
                    "{\"name\":\"Knit\",\"category\":\"TOP\",\"primaryColourHex\":\"red\"}"
                }) {
            mvc.perform(
                            as(post("/api/v1/garments"), "invalid-garment")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"));
        }
        String id =
                create("invalid-garment", "{\"name\":\"Knit\",\"category\":\"TOP\"}")
                        .get("id")
                        .asText();
        mvc.perform(
                        as(patch("/api/v1/garments/" + id), "invalid-garment")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"wearCount\":300}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        as(patch("/api/v1/garments/" + id), "invalid-garment")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Missing version\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/garments").param("limit", "1000"), "invalid-garment"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullTextAndStructuredFiltersAreCombinedAsHardConstraints() throws Exception {
        create(
                "garment-filters",
                "{\"name\":\"Cream winter knit\",\"category\":\"TOP\",\"seasonTags\":[\"WINTER\"],\"primaryColourName\":\"Cream\"}");
        create(
                "garment-filters",
                "{\"name\":\"Cream summer dress\",\"category\":\"DRESS\",\"seasonTags\":[\"SUMMER\"]}");
        mvc.perform(
                        as(
                                get("/api/v1/garments")
                                        .param("q", "cream")
                                        .param("category", "TOP")
                                        .param("season", "WINTER")
                                        .param("colour", "cream"),
                                "garment-filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Cream winter knit"));
        mvc.perform(as(get("/api/v1/garments").param("brand", "' OR 1=1 --"), "garment-filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void oneThousandGarmentsPaginateWithoutGapsOrDuplicates() throws Exception {
        String subject = "large-catalogue";
        seed(subject, 1000);
        assertThat(allPages(subject, GarmentSort.NEWEST, 73)).hasSize(1000);
    }

    @ParameterizedTest
    @EnumSource(GarmentSort.class)
    void everySortPaginatesTiesAndMissingValues(GarmentSort sort) throws Exception {
        String subject = "sort-" + sort.name();
        seed(subject, 11);
        assertThat(allPages(subject, sort, 3)).hasSize(11);
    }

    @Test
    void cursorCannotBeReusedWithDifferentFilters() throws Exception {
        seed("cursor-filters", 3);
        JsonNode page = response(as(get("/api/v1/garments").param("limit", "1"), "cursor-filters"));
        mvc.perform(
                        as(
                                get("/api/v1/garments")
                                        .param("category", "SHOES")
                                        .param("cursor", page.get("nextCursor").asText()),
                                "cursor-filters"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/garments").param("cursor", "malformed"), "cursor-filters"))
                .andExpect(status().isBadRequest());
    }

    private Set<String> allPages(String subject, GarmentSort sort, int limit) throws Exception {
        Set<String> ids = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var request =
                    as(
                            get("/api/v1/garments")
                                    .param("limit", Integer.toString(limit))
                                    .param("sort", sort.name()),
                            subject);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = response(request);
            for (JsonNode garment : page.get("items")) {
                assertThat(ids.add(garment.get("id").asText()))
                        .as("each garment occurs once")
                        .isTrue();
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            assertThat(++pages).isLessThan(100);
        } while (cursor != null);
        return ids;
    }

    private void seed(String subject, int count) throws Exception {
        UUID wardrobe =
                UUID.fromString(
                        response(as(get("/api/v1/wardrobes/current"), subject)).get("id").asText());
        jdbc.sql(
                        """
                INSERT INTO garment (id, wardrobe_id, name, category, purchase_price, purchase_currency,
                    wear_count_cached, last_worn_at, created_at, updated_at)
                SELECT gen_random_uuid(), :wardrobe, 'Piece ' || (n % 4), 'TOP',
                    CASE WHEN n % 3 = 0 THEN NULL ELSE 30 END, 'GBP', n % 4,
                    CASE WHEN n % 3 = 0 THEN NULL ELSE DATE '2026-01-01' END,
                    TIMESTAMPTZ '2026-01-01 00:00:00Z', TIMESTAMPTZ '2026-01-01 00:00:00Z'
                FROM generate_series(1, :count) n
                """)
                .param("wardrobe", wardrobe)
                .param("count", count)
                .update();
    }

    private JsonNode create(String subject, String body) throws Exception {
        String result =
                mvc.perform(
                                as(post("/api/v1/garments"), subject)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(result);
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private MockHttpServletRequestBuilder as(
            MockHttpServletRequestBuilder request, String subject) {
        return request.with(jwt().jwt(token -> token.subject(subject)));
    }
}
