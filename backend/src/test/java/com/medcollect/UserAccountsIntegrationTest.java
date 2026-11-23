package com.medcollect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-accounts;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "medcollect.admin-user=existing-admin",
        "medcollect.admin-password=existing-admin-fixture",
        "medcollect.reviewer-user=existing-reviewer",
        "medcollect.reviewer-password=existing-reviewer-fixture",
        "medcollect.scheduling-enabled=false"
})
@AutoConfigureMockMvc
class UserAccountsIntegrationTest {
    @TempDir static Path originals;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store store;
    @Autowired UserAccounts accounts;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("medcollect.original-dir", () -> originals.toString());
    }

    @Test
    void registersReviewersAndLetsAdministratorsManagePersistentAccounts() throws Exception {
        MockHttpSession admin = signIn("existing-admin", "existing-admin-fixture");
        MockHttpSession reviewer = signIn("existing-reviewer", "existing-reviewer-fixture");
        assertThat(body(mvc.perform(get("/api/session").session(admin)), 200).path("admin").asBoolean()).isTrue();
        mvc.perform(get("/api/users").session(reviewer)).andExpect(status().isForbidden());

        JsonNode existingAdmin = body(mvc.perform(get("/api/users").param("query", "existing-admin").session(admin)), 200)
                .path("rows").path(0);
        mvc.perform(put("/api/users/{id}", existingAdmin.path("id").asText()).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "role", "REVIEWER", "enabled", true, "revision", existingAdmin.path("revision").asInt()))))
                .andExpect(status().isConflict());

        byte[] registration = json.writeValueAsBytes(Map.of("username", "NewReviewer", "password", "first-fixture-pass",
                "role", "ADMIN"));
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isForbidden());
        JsonNode created = body(mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(registration).with(csrf())), 201);
        assertThat(created.path("role").asText()).isEqualTo("REVIEWER");
        assertThat(created.has("passwordHash")).isFalse();
        String id = created.path("id").asText();
        String hash = store.db().sql("SELECT password_hash FROM mc_user_account WHERE id = :id")
                .param("id", id).query(String.class).single();
        assertThat(hash).startsWith("$2").isNotEqualTo("first-fixture-pass");
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", "newreviewer", "password", "duplicate-fixture")))
                .with(csrf())).andExpect(status().isConflict());

        MockHttpSession registered = signIn("newreviewer", "first-fixture-pass");
        assertThat(body(mvc.perform(get("/api/session").session(registered)), 200).path("username").asText())
                .isEqualTo("NewReviewer");
        mvc.perform(get("/api/users").session(registered)).andExpect(status().isForbidden());

        JsonNode disabled = update(admin, id, Map.of("role", "REVIEWER", "enabled", false,
                "revision", created.path("revision").asInt()));
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        mvc.perform(get("/api/session").session(registered)).andExpect(status().isUnauthorized());
        signInRequest("NewReviewer", "first-fixture-pass").andExpect(status().isUnauthorized());

        JsonNode promoted = update(admin, id, Map.of("role", "ADMIN", "enabled", true,
                "revision", disabled.path("revision").asInt(), "password", "reset-fixture-pass"));
        assertThat(promoted.path("role").asText()).isEqualTo("ADMIN");
        signInRequest("NewReviewer", "first-fixture-pass").andExpect(status().isUnauthorized());
        MockHttpSession newAdmin = signIn("NewReviewer", "reset-fixture-pass");
        mvc.perform(get("/api/users").session(newAdmin)).andExpect(status().isOk());

        JsonNode extra = body(mvc.perform(post("/api/users").session(newAdmin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "username", "managed-reviewer", "role", "REVIEWER", "password", "managed-fixture-pass")))), 201);
        assertThat(extra.path("username").asText()).isEqualTo("managed-reviewer");

        update(newAdmin, existingAdmin.path("id").asText(), Map.of("role", "ADMIN", "enabled", true,
                "revision", existingAdmin.path("revision").asInt(), "password", "rotated-bootstrap-pass"));
        accounts.bootstrap();
        mvc.perform(get("/api/session").session(admin)).andExpect(status().isUnauthorized());
        signInRequest("existing-admin", "existing-admin-fixture").andExpect(status().isUnauthorized());
        signIn("existing-admin", "rotated-bootstrap-pass");
    }

    private ResultActions signInRequest(String username, String password) throws Exception {
        return mvc.perform(post("/api/login").param("username", username).param("password", password).with(csrf()));
    }

    private MockHttpSession signIn(String username, String password) throws Exception {
        return (MockHttpSession) signInRequest(username, password).andExpect(status().isNoContent())
                .andReturn().getRequest().getSession(false);
    }

    private JsonNode update(MockHttpSession admin, String id, Map<String, Object> fields) throws Exception {
        return body(mvc.perform(put("/api/users/{id}", id).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(fields))), 200);
    }

    private JsonNode body(ResultActions result, int code) throws Exception {
        return json.readTree(result.andExpect(status().is(code)).andReturn().getResponse().getContentAsByteArray());
    }
}
