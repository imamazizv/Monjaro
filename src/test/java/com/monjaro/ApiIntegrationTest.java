package com.monjaro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-signing-key-at-least-32-bytes-long");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;

    private record Account(String id, String email, String token) {}

    private Account account() throws Exception {
        String username = "u" + UUID.randomUUID().toString().replace("-", "");
        String email = username + "@example.com";
        JsonNode result = request(post("/api/auth/register"), null,
                Map.of("username", username, "email", email, "password", "a-long-test-password"), 201);
        return new Account(result.at("/user/id").asText(), email, result.get("accessToken").asText());
    }

    private JsonNode request(MockHttpServletRequestBuilder builder, String token, Object body, int status) throws Exception {
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String result = mvc.perform(builder).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return result.isEmpty() ? json.getNodeFactory().nullNode() : json.readTree(result);
    }

    private String project(Account owner) throws Exception {
        return request(post("/api/projects"), owner.token(), Map.of("name", "Internship tracker"), 201).get("id").asText();
    }

    private void addMember(String project, Account owner, Account member, String role) throws Exception {
        request(post("/api/projects/" + project + "/members"), owner.token(), Map.of("email", member.email(), "role", role), 201);
    }

    @Test
    void authenticationValidationAndPasswordStorage() throws Exception {
        Account user = account();
        JsonNode login = request(post("/api/auth/login"), null,
                Map.of("email", user.email().toUpperCase(Locale.ROOT), "password", "a-long-test-password"), 200);
        assertThat(login.get("accessToken").asText()).isNotBlank();
        request(get("/api/users/me"), user.token(), null, 200);
        request(post("/api/auth/login"), null, Map.of("email", user.email(), "password", "wrong"), 401);
        request(post("/api/auth/login"), null, Map.of("email", "missing@example.com", "password", "wrong"), 401);
        request(post("/api/auth/register"), null,
                Map.of("username", "another_user", "email", user.email(), "password", "a-long-test-password"), 409);
        request(post("/api/auth/register"), null,
                Map.of("username", "bad", "email", "not-an-email", "password", "short"), 400);
        request(get("/api/projects"), null, null, 401);
        request(get("/api/projects"), "invalid-token", null, 401);
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = ?").param(UUID.fromString(user.id())).query(String.class).single();
        assertThat(hash).startsWith("$2").isNotEqualTo("a-long-test-password");
        JsonNode profile = request(get("/api/users/me"), user.token(), null, 200);
        assertThat(profile.has("passwordHash")).isFalse();
    }

    @Test
    void expiredTokensAreRejected() throws Exception {
        Account user = account();
        var claims = JwtClaimsSet.builder().issuer("monjaro").subject(user.id())
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(300)).build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        request(get("/api/projects"), expired, null, 401);
    }

    @Test
    void permissionsAreEnforcedAndOutsidersCannotSeeProjects() throws Exception {
        Account owner = account(), member = account(), viewer = account(), outsider = account();
        String id = project(owner), base = "/api/projects/" + id;
        addMember(id, owner, member, "MEMBER");
        addMember(id, owner, viewer, "VIEWER");
        request(get(base), outsider.token(), null, 404);
        request(get(base + "/tasks"), outsider.token(), null, 404);
        request(get(base + "/members"), outsider.token(), null, 404);
        request(get(base + "/progress"), outsider.token(), null, 404);
        assertThat(request(get("/api/projects"), outsider.token(), null, 200).get("totalElements").asInt()).isZero();
        request(get(base), viewer.token(), null, 200);
        request(post(base + "/tasks"), viewer.token(), Map.of("title", "Forbidden"), 403);
        request(patch(base), member.token(), Map.of("name", "Forbidden"), 403);
        request(delete(base), member.token(), null, 403);
        request(post(base + "/members"), member.token(), Map.of("email", outsider.email(), "role", "MEMBER"), 403);
        request(patch(base + "/members/" + viewer.id()), member.token(), Map.of("role", "MEMBER"), 403);
        request(delete(base + "/members/" + member.id()), member.token(), null, 403);
        request(delete(base + "/members/" + owner.id()), owner.token(), null, 409);
        request(patch(base + "/members/" + owner.id()), owner.token(), Map.of("role", "MEMBER"), 409);
        request(post(base + "/members"), owner.token(), Map.of("email", outsider.email(), "role", "OWNER"), 400);
        JsonNode task = request(post(base + "/tasks"), member.token(), Map.of("title", "Allowed"), 201);
        String taskPath = base + "/tasks/" + task.get("id").asText();
        request(patch(taskPath), viewer.token(), Map.of("status", "DONE"), 403);
        request(delete(taskPath), viewer.token(), null, 403);
        request(patch(taskPath), member.token(), Map.of("status", "IN_PROGRESS"), 200);
        request(patch(base + "/members/" + member.id()), owner.token(), Map.of("role", "VIEWER"), 200);
        // The old JWT must not retain a stale role after demotion.
        request(patch(taskPath), member.token(), Map.of("status", "DONE"), 403);
    }

    @Test
    void taskLifecycleFilteringProgressAndRemoval() throws Exception {
        Account owner = account(), member = account(), outsider = account();
        String id = project(owner), base = "/api/projects/" + id;
        addMember(id, owner, member, "MEMBER");
        assertThat(request(get(base + "/progress"), owner.token(), null, 200).get("completionPercentage").asDouble()).isZero();
        JsonNode task = request(post(base + "/tasks"), owner.token(),
                Map.of("title", "Implement login", "assigneeId", member.id(), "priority", "HIGH", "dueDate", "2027-01-01"), 201);
        String path = base + "/tasks/" + task.get("id").asText();
        request(post(base + "/tasks"), owner.token(), Map.of("title", "Write docs"), 201);
        request(patch(path), member.token(), Map.of("status", "DONE"), 200);
        JsonNode progress = request(get(base + "/progress"), member.token(), null, 200);
        assertThat(progress.get("total").asLong()).isEqualTo(2);
        assertThat(progress.get("done").asLong()).isEqualTo(1);
        assertThat(progress.get("todo").asLong()).isEqualTo(1);
        assertThat(progress.get("completionPercentage").asDouble()).isEqualTo(50.0);
        JsonNode filtered = request(get(base + "/tasks").param("status", "DONE").param("priority", "HIGH")
                .param("assigneeId", member.id()).param("size", "1"), owner.token(), null, 200);
        assertThat(filtered.get("totalElements").asInt()).isEqualTo(1);
        assertThat(filtered.get("content").size()).isEqualTo(1);
        assertThat(request(get(base + "/tasks").param("page", "1").param("size", "1"), owner.token(), null, 200)
                .get("content").size()).isEqualTo(1);
        request(get(base + "/tasks").param("size", "101"), owner.token(), null, 400);
        request(get(base + "/tasks").param("page", "-1"), owner.token(), null, 400);
        request(post(base + "/tasks"), owner.token(), Map.of("title", "Bad assignee", "assigneeId", outsider.id()), 400);
        request(patch(path), owner.token(), Map.of("assigneeId", outsider.id()), 400);
        request(patch(path), owner.token(), Map.of("status", "INVALID"), 400);
        request(post(base + "/tasks"), owner.token(), Map.of("title", " "), 400);
        request(patch(path), owner.token(), Map.of("title", " "), 400);
        request(patch(path), owner.token(), Map.of("unknownField", "value"), 400);
        Map<String, Object> clear = new HashMap<>();
        clear.put("assigneeId", null); clear.put("dueDate", null);
        JsonNode cleared = request(patch(path), owner.token(), clear, 200);
        assertThat(cleared.get("assigneeId").isNull()).isTrue();
        assertThat(cleared.get("dueDate").isNull()).isTrue();
        assertThat(cleared.get("title").asText()).isEqualTo("Implement login");
        request(patch(path), owner.token(), Map.of("assigneeId", member.id()), 200);
        request(delete(base + "/members/" + member.id()), owner.token(), null, 204);
        assertThat(request(get(path), owner.token(), null, 200).get("assigneeId").isNull()).isTrue();
        request(get(path), member.token(), null, 404);
        String anotherProject = project(owner);
        request(get("/api/projects/" + anotherProject + "/tasks/" + task.get("id").asText()), owner.token(), null, 404);
        request(delete(path), owner.token(), null, 204);
        request(get(path), owner.token(), null, 404);
        request(delete(base), owner.token(), null, 204);
        request(get(base), owner.token(), null, 404);
    }
}
