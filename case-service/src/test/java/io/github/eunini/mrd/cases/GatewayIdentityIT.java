package io.github.eunini.mrd.cases;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies real workspace identities and independent filing decisions at the Java boundary. */
class GatewayIdentityIT extends IntegrationTestBase {
    private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String OWNER = "u_" + UUID.randomUUID();
    private static final String REVIEWER = "u_" + UUID.randomUUID();

    @DynamicPropertySource
    static void signingProperty(DynamicPropertyRegistry registry) {
        registry.add("mrd.gateway-secret", () -> SECRET);
    }

    private RequestPostProcessor identity(String actor, String role, long time, boolean tamper) throws Exception {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of(
                "actor", actor, "role", role, "time", time,
                "members", List.of(Map.of("actor", actor, "role", role, "name", "Workspace member")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        return request -> {
            request.addHeader("X-Workspace-Identity", payload);
            request.addHeader("X-Workspace-Signature", tamper ? "0".repeat(64) : signature);
            return request;
        };
    }

    private RequestPostProcessor identity(String actor, String role) throws Exception {
        return identity(actor, role, Instant.now().getEpochSecond(), false);
    }

    @Test
    void signedIdentityPreservesActorAndPrivileges() throws Exception {
        mvc.perform(get("/api/me").with(identity(OWNER, "ANALYST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(OWNER));
        mvc.perform(get("/api/me").with(identity(REVIEWER, "SUPERVISOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.hasItem("SUPERVISOR")));
        postJson("/api/alerts", identity(OWNER, "ANALYST"), List.of())
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedStaleAndUnexpectedRoleEnvelopesAreRejected() throws Exception {
        mvc.perform(get("/api/cases").with(identity(OWNER, "SUPERVISOR", Instant.now().getEpochSecond(), true)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cases").with(identity(OWNER, "ANALYST", Instant.now().getEpochSecond() - 120, false)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cases").with(identity(OWNER, "INGEST")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workspaceFilingNeedsAnIndependentSupervisor() throws Exception {
        long id = caseIdOf(ingest(fixture()), 0);
        transition(id, identity(OWNER, "SUPERVISOR"), "INVESTIGATING", null).andExpect(status().isOk());
        transition(id, identity(OWNER, "SUPERVISOR"), "ESCALATED", null).andExpect(status().isOk());
        mvc.perform(get("/api/cases/" + id + "/str.xml").with(identity(OWNER, "SUPERVISOR")))
                .andExpect(status().isOk());
        postJson("/api/cases/" + id + "/filing-request", identity(OWNER, "SUPERVISOR"), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.filingRequest.requestedBy").value(OWNER));
        postJson("/api/cases/" + id + "/filing-approval", identity(OWNER, "SUPERVISOR"), Map.of())
                .andExpect(status().isForbidden());
        postJson("/api/cases/" + id + "/filing-approval", identity(REVIEWER, "ANALYST"), Map.of())
                .andExpect(status().isForbidden());
        postJson("/api/cases/" + id + "/filing-approval", identity(REVIEWER, "SUPERVISOR"), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.filingRequest.decidedBy").value(REVIEWER));
    }
}
