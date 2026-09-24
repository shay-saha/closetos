package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
class HistoryInsightsHttpTest extends PostgresIntegrationTest {
    private static final String ROOT = "/api/v1/insights/";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @Test
    void authenticationAndRequestBoundsAreEnforcedForEveryHistoryEndpoint() throws Exception {
        for (String endpoint : List.of("usage", "cost-per-wear", "forgotten")) {
            mvc.perform(get(ROOT + endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(as(get(ROOT + endpoint).param("limit", "0"), "insights-validation"))
                    .andExpect(status().isBadRequest());
            mvc.perform(as(get(ROOT + endpoint).param("limit", "101"), "insights-validation"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(get(ROOT + "forgotten").param("season", "invalid"), "insights-validation"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        as(
                                get(ROOT + "usage").param("includeArchived", "invalid"),
                                "insights-validation"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void countsAndCurrencyGroupsStayOwnerScopedAndDoNotInferMissingPrices() throws Exception {
        String owner = "insights-owner";
        piece(owner, "Pound purchase", "GBP", "60", "autumn");
        piece(owner, "Dollar purchase", "USD", "200", "autumn");
        piece(owner, "Missing price", "GBP", null, "autumn");
        piece(owner, "Missing currency", null, "500", "autumn");
        UUID foreign = piece("insights-foreign", "Private expensive coat", "GBP", "9000", "autumn");
        UUID foreignWardrobe =
                jdbc.sql("SELECT wardrobe_id FROM garment WHERE id = :id")
                        .param("id", foreign)
                        .query(UUID.class)
                        .single();
        var usage =
                read(owner, get(ROOT + "usage").param("wardrobeId", foreignWardrobe.toString()));
        assertThat(usage.path("garmentCount").asInt()).isEqualTo(4);
        assertThat(usage.path("neverWornCount").asInt()).isEqualTo(4);
        assertThat(usage.path("garmentWearOccurrences").asLong()).isZero();
        assertThat(usage.path("mostWorn")).isEmpty();
        var costs =
                read(
                        owner,
                        get(ROOT + "cost-per-wear")
                                .param("wardrobeId", foreignWardrobe.toString()));
        assertThat(costs.path("missingPriceCount").asInt()).isEqualTo(1);
        assertThat(costs.path("missingCurrencyCount").asInt()).isEqualTo(1);
        assertThat(costs.path("currencies")).hasSize(2);
        assertThat(costs.path("currencies").get(0).path("currency").asText()).isEqualTo("GBP");
        assertThat(costs.path("currencies").get(0).path("knownPurchaseTotal").decimalValue())
                .isEqualByComparingTo("60");
        assertThat(costs.path("currencies").get(0).path("lowestCostPerWear")).isEmpty();
        assertThat(
                        costs.path("currencies")
                                .get(0)
                                .path("neverWorn")
                                .get(0)
                                .path("costPerWear")
                                .decimalValue())
                .isEqualByComparingTo("60");
        assertThat(usage.toString() + costs.toString())
                .doesNotContain("Private expensive coat", foreign.toString(), "Infinity", "NaN");
        assertThat(read("insights-empty-owner", get(ROOT + "cost-per-wear")).path("currencies"))
                .isEmpty();
    }

    @Test
    void loggedAndRemovedWearImmediatelyUpdatesInsightsWithoutASecondHistorySource()
            throws Exception {
        String owner = "insights-wear";
        UUID garment = piece(owner, "Recorded purchase", "GBP", "90", "autumn");
        UUID first = wear(owner, garment, LocalDate.now(ZoneOffset.UTC).minusDays(3));
        UUID second = wear(owner, garment, LocalDate.now(ZoneOffset.UTC).minusDays(1));
        var usage = read(owner, get(ROOT + "usage"));
        assertThat(usage.path("garmentWearOccurrences").asLong()).isEqualTo(2);
        assertThat(usage.path("neverWornCount").asInt()).isZero();
        assertThat(lowestCost(owner)).isEqualByComparingTo("45");
        assertThat(read(owner, get(ROOT + "forgotten")).path("items")).isEmpty();
        mvc.perform(as(delete("/api/v1/wear-events/" + second), owner))
                .andExpect(status().isNoContent());
        assertThat(lowestCost(owner)).isEqualByComparingTo("90");
        mvc.perform(as(delete("/api/v1/wear-events/" + first), owner))
                .andExpect(status().isNoContent());
        assertThat(read(owner, get(ROOT + "usage")).path("neverWornCount").asInt()).isEqualTo(1);
        var unused = read(owner, get(ROOT + "forgotten")).path("items").get(0);
        assertThat(unused.path("garment").path("id").asText()).isEqualTo(garment.toString());
        assertThat(unused.path("daysSinceLastWear").isNull()).isTrue();
        assertThat(
                        read(owner, get(ROOT + "cost-per-wear"))
                                .path("currencies")
                                .get(0)
                                .path("lowestCostPerWear"))
                .isEmpty();
    }

    @Test
    void archiveAndReviewStatusControlEligibilityAndAllCountsReflectTheFullWardrobe()
            throws Exception {
        String owner = "insights-status";
        UUID available = piece(owner, "Available", "GBP", "20", "autumn");
        UUID archived = piece(owner, "Archived", "GBP", "100", "autumn");
        UUID unreviewed = piece(owner, "Unreviewed", "GBP", "300", "autumn");
        jdbc.sql("UPDATE garment SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", archived)
                .update();
        jdbc.sql("UPDATE garment SET processing_status = 'READY_FOR_REVIEW' WHERE id = :id")
                .param("id", unreviewed)
                .update();
        var normal = read(owner, get(ROOT + "usage"));
        assertThat(normal.path("garmentCount").asInt()).isEqualTo(1);
        assertThat(normal.path("leastWorn").get(0).path("id").asText())
                .isEqualTo(available.toString());
        var all =
                read(
                        owner,
                        get(ROOT + "usage").param("includeArchived", "true").param("limit", "1"));
        assertThat(all.path("garmentCount").asInt()).isEqualTo(2);
        assertThat(all.path("neverWornCount").asInt()).isEqualTo(2);
        assertThat(all.path("leastWorn")).hasSize(1);
        assertThat(all.path("availability").path("ARCHIVED").asInt()).isEqualTo(1);
        var costs = read(owner, get(ROOT + "cost-per-wear").param("includeArchived", "true"));
        assertThat(costs.path("currencies").get(0).path("knownPurchaseTotal").decimalValue())
                .isEqualByComparingTo("120");
        var forgotten = read(owner, get(ROOT + "forgotten"));
        assertThat(forgotten.path("eligibleCount").asInt()).isEqualTo(1);
        assertThat(forgotten.path("items").get(0).path("garment").path("id").asText())
                .isEqualTo(available.toString());
    }

    @Test
    void forgottenExplainsSeasonAndAvailabilityAndUsesAddedDateWhenOwnershipIsUnknown()
            throws Exception {
        String owner = "insights-forgotten";
        UUID winter = piece(owner, "Winter piece", null, null, "winter");
        UUID summer = piece(owner, "Summer piece", null, null, "summer");
        UUID laundry = piece(owner, "Laundry piece", null, null, "winter");
        jdbc.sql("UPDATE garment SET status = 'LAUNDRY' WHERE id = :id")
                .param("id", laundry)
                .update();
        var result =
                read(owner, get(ROOT + "forgotten").param("season", "WINTER").param("limit", "2"));
        assertThat(result.path("eligibleCount").asInt()).isEqualTo(3);
        assertThat(result.path("items")).hasSize(2);
        assertThat(result.path("items").get(0).path("garment").path("id").asText())
                .isEqualTo(winter.toString());
        assertThat(result.path("items").get(1).path("garment").path("id").asText())
                .isEqualTo(summer.toString());
        assertThat(result.path("items").get(0).path("seasonCompatibility").asText())
                .isEqualTo("MATCH");
        assertThat(result.path("items").get(1).path("seasonCompatibility").asText())
                .isEqualTo("MISMATCH");
        jdbc.sql(
                        "UPDATE garment SET purchase_date = NULL, created_at = now() - interval '400 days' WHERE id = :id")
                .param("id", winter)
                .update();
        var noSeason = read(owner, get(ROOT + "forgotten"));
        JsonNode fallback = null;
        for (var item : noSeason.path("items"))
            if (item.path("garment").path("id").asText().equals(winter.toString())) fallback = item;
        assertThat(fallback).isNotNull();
        assertThat(fallback.path("ownershipBasis").asText()).isEqualTo("ADDED_DATE");
        assertThat(fallback.path("seasonCompatibility").asText()).isEqualTo("NOT_REQUESTED");
        assertThat(fallback.path("reasons").toString())
                .contains("purchase date is unknown", "not inferred");
    }

    private UUID piece(String owner, String name, String currency, String price, String season)
            throws Exception {
        var body =
                json.createObjectNode()
                        .put("name", name)
                        .put("category", "TOP")
                        .put(
                                "purchaseDate",
                                LocalDate.now(ZoneOffset.UTC).minusDays(400).toString());
        if (price != null) body.put("purchasePrice", new java.math.BigDecimal(price));
        if (currency != null || price != null)
            body.put("purchaseCurrency", currency == null ? "GBP" : currency);
        body.set("seasonTags", json.createArrayNode().add(season));
        UUID id =
                UUID.fromString(
                        json.readTree(
                                        mvc.perform(
                                                        as(post("/api/v1/garments"), owner)
                                                                .contentType(
                                                                        MediaType.APPLICATION_JSON)
                                                                .content(body.toString()))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .path("id")
                                .asText());
        if (price != null && currency == null)
            jdbc.sql("UPDATE garment SET purchase_currency = NULL WHERE id = :id")
                    .param("id", id)
                    .update();
        return id;
    }

    private UUID wear(String owner, UUID garment, LocalDate wornOn) throws Exception {
        ObjectNode body = json.createObjectNode().put("wornOn", wornOn.toString());
        body.set("garmentIds", json.createArrayNode().add(garment.toString()));
        return UUID.fromString(
                json.readTree(
                                mvc.perform(
                                                as(post("/api/v1/wear-events"), owner)
                                                        .header(
                                                                "Idempotency-Key",
                                                                UUID.randomUUID().toString())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(body.toString()))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("id")
                        .asText());
    }

    private java.math.BigDecimal lowestCost(String owner) throws Exception {
        return read(owner, get(ROOT + "cost-per-wear"))
                .path("currencies")
                .get(0)
                .path("lowestCostPerWear")
                .get(0)
                .path("costPerWear")
                .decimalValue();
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
