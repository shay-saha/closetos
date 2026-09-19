package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class WardrobeHttpTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void healthIsPublicButWardrobeRequiresAuthentication() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/wardrobes/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void provisioningIsStableAndOwnerScoped() throws Exception {
        JsonNode first = current("wardrobe-user-a");
        assertThat(current("wardrobe-user-a").get("id")).isEqualTo(first.get("id"));
        assertThat(current("wardrobe-user-b").get("id")).isNotEqualTo(first.get("id"));
    }

    @Test
    void renameRejectsStaleWritesAndInvalidNames() throws Exception {
        current("wardrobe-rename");
        mvc.perform(
                        patch("/api/v1/wardrobes/current")
                                .with(jwt().jwt(token -> token.subject("wardrobe-rename")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Everyday pieces\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Everyday pieces"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(
                        patch("/api/v1/wardrobes/current")
                                .with(jwt().jwt(token -> token.subject("wardrobe-rename")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Stale\",\"version\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        patch("/api/v1/wardrobes/current")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\" \",\"version\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renameRequiresAnExplicitVersion() throws Exception {
        mvc.perform(
                        patch("/api/v1/wardrobes/current")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"New name\"}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode current(String subject) throws Exception {
        String body =
                mvc.perform(
                                get("/api/v1/wardrobes/current")
                                        .with(jwt().jwt(token -> token.subject(subject))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body);
    }
}
