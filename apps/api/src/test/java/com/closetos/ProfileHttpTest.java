package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureMockMvc
class ProfileHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void preferencesPersistForTheAuthenticatedOwnerAndRejectStaleWrites() throws Exception {
        JsonNode initial = profile("profile-owner");
        ObjectNode update = update();
        mvc.perform(
                        patch("/api/v1/me")
                                .with(jwt().jwt(token -> token.subject("profile-owner")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(update.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("My name"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.deleteOriginalAfterIsolation").value(true))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(profile("profile-owner").path("timezone").asText()).isEqualTo("Europe/Paris");
        JsonNode other = profile("profile-other");
        assertThat(other.path("id").asText()).isNotEqualTo(initial.path("id").asText());
        assertThat(other.path("deleteOriginalAfterIsolation").asBoolean()).isFalse();
        assertThat(other.path("currency").asText()).isEqualTo("GBP");
        mvc.perform(
                        patch("/api/v1/me")
                                .with(jwt().jwt(token -> token.subject("profile-owner")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(update.toString()))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidLocaleCurrencyTimezoneAndReadOnlyFieldsAreRejected() throws Exception {
        String owner = "profile-invalid";
        for (var invalid :
                Map.of(
                                "locale",
                                "not_a_tag",
                                "currency",
                                "ZZZ",
                                "timezone",
                                "Mars/Olympus",
                                "id",
                                "another-owner")
                        .entrySet()) {
            ObjectNode request = update().put(invalid.getKey(), invalid.getValue());
            mvc.perform(
                            patch("/api/v1/me")
                                    .with(jwt().jwt(token -> token.subject(owner)))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(request.toString()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(profile(owner).path("version").asInt()).isZero();
    }

    @Test
    void unauthenticatedRequestsCannotReadOrChangePreferences() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(
                        patch("/api/v1/me")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(update().toString()))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode profile(String subject) throws Exception {
        return json.readTree(
                mvc.perform(get("/api/v1/me").with(jwt().jwt(token -> token.subject(subject))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private ObjectNode update() {
        return json.createObjectNode()
                .put("displayName", "My name")
                .put("locale", "fr-FR")
                .put("currency", "EUR")
                .put("timezone", "Europe/Paris")
                .put("deleteOriginalAfterIsolation", true)
                .put("version", 0);
    }
}
