package io.github.eunini.mrd.cases;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Shared Spring context (H2 in PostgreSQL mode + Flyway) and request helpers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final RequestPostProcessor ENGINE = httpBasic("engine", "test-engine");
    protected static final RequestPostProcessor ANALYST1 = httpBasic("analyst1", "test-analyst1");
    protected static final RequestPostProcessor ANALYST2 = httpBasic("analyst2", "test-analyst2");
    protected static final RequestPostProcessor SUPERVISOR1 = httpBasic("supervisor1", "test-supervisor1");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM filing_request");
        jdbc.update("DELETE FROM report");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM case_alert");
        jdbc.update("DELETE FROM case_account");
        jdbc.update("DELETE FROM alert_account");
        jdbc.update("DELETE FROM alert");
        jdbc.update("UPDATE investigation_case SET merged_into_id = NULL");
        jdbc.update("DELETE FROM investigation_case");
    }

    protected String fixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/alerts-two-rings.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    protected JsonNode ingest(String body) throws Exception {
        String response = mvc.perform(post("/api/alerts").with(ENGINE)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    protected JsonNode ingest(ObjectNode... alerts) throws Exception {
        ArrayNode array = json.createArrayNode();
        for (ObjectNode alert : alerts) {
            array.add(alert);
        }
        return ingest(json.writeValueAsString(array));
    }

    /** Minimal valid alert between two accounts. */
    protected ObjectNode alert(String alertId, String from, String to, String ringId, String... ringAccounts) {
        ObjectNode node = json.createObjectNode();
        node.put("alertId", alertId);
        node.put("txId", Math.abs(alertId.hashCode()) % 1_000_000L + 1);
        node.put("timestamp", "2022-09-09T10:20:00Z");
        node.put("fromAccount", from);
        node.put("toAccount", to);
        node.put("amountUsd", 1000.50);
        node.put("currency", "US Dollar");
        node.put("paymentFormat", "ACH");
        node.put("score", 0.8);
        node.put("threshold", 0.71);
        node.put("modelVersion", "gbdt-v1");
        if (ringId != null) {
            node.put("ringId", ringId);
        }
        ArrayNode ring = node.putArray("ringAccounts");
        for (String account : ringAccounts) {
            ring.add(account);
        }
        ArrayNode detectors = node.putArray("detectors");
        ObjectNode detector = detectors.addObject();
        detector.put("name", "pass_through");
        detector.put("value", 0.95);
        detector.put("detail", "forwarded 95% within 24h");
        ObjectNode edge = detector.putArray("edges").addObject();
        edge.put("txId", node.get("txId").asLong() + 5_000_000L);
        edge.put("from", from);
        edge.put("to", to);
        edge.put("amountUsd", 1000.50);
        edge.put("timestamp", "2022-09-09T09:00:00Z");
        return node;
    }

    protected long caseIdOf(JsonNode ingestResponse, int index) {
        return ingestResponse.get("results").get(index).get("caseId").asLong();
    }

    protected JsonNode getJson(String path, RequestPostProcessor user) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    protected ResultActions postJson(String path, RequestPostProcessor user, Object body) throws Exception {
        return mvc.perform(post(path).with(user).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions transition(long caseId, RequestPostProcessor user, String to, String disposition)
            throws Exception {
        Map<String, Object> body = disposition == null
                ? Map.of("to", to, "comment", "moving to " + to)
                : Map.of("to", to, "disposition", disposition, "comment", "closing");
        return postJson("/api/cases/" + caseId + "/transition", user, body);
    }

    protected List<String> auditActions(long caseId) throws Exception {
        JsonNode audit = getJson("/api/cases/" + caseId + "/audit", ANALYST1);
        return java.util.stream.StreamSupport.stream(audit.spliterator(), false)
                .map(e -> e.get("action").asText()).toList();
    }

    /** Moves a case to ESCALATED via the normal workflow. */
    protected void escalate(long caseId) throws Exception {
        transition(caseId, ANALYST1, "INVESTIGATING", null).andExpect(status().isOk());
        transition(caseId, ANALYST1, "ESCALATED", null).andExpect(status().isOk());
    }
}
