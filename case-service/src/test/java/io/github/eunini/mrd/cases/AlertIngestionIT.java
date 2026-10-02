package io.github.eunini.mrd.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AlertIngestionIT extends IntegrationTestBase {

    @Test
    void fixtureAlertsAreStoredAndGroupedIntoTwoCases() throws Exception {
        JsonNode response = ingest(fixture());

        assertThat(response.get("received").asInt()).isEqualTo(6);
        assertThat(response.get("accepted").asInt()).isEqualTo(6);
        assertThat(response.get("duplicates").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM investigation_case", Integer.class)).isEqualTo(2);
        assertThat(caseIdOf(response, 0)).isEqualTo(caseIdOf(response, 2));
        assertThat(caseIdOf(response, 3)).isEqualTo(caseIdOf(response, 5));
        assertThat(caseIdOf(response, 0)).isNotEqualTo(caseIdOf(response, 3));
        assertThat(response.get("results").get(0).get("caseReference").asText()).matches("CASE-\\d{4}-\\d{6}");

        String evidence = jdbc.queryForObject("SELECT evidence_json FROM alert WHERE alert_id = 'txn-200009'", String.class);
        assertThat(evidence).contains("\"cycle\"").contains("\"edges\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_account WHERE role = 'RING'", Integer.class))
                .isPositive();
    }

    @Test
    void repostingTheSameAlertsIsANoOp() throws Exception {
        ingest(fixture());
        long auditRows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);

        JsonNode again = ingest(fixture());

        assertThat(again.get("accepted").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(6);
        assertThat(again.get("results").get(0).get("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(again.get("results").get(0).get("caseId").isNumber()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM investigation_case", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isEqualTo(auditRows);
        assertThat(jdbc.queryForObject("SELECT SUM(alert_count) FROM investigation_case", Integer.class)).isEqualTo(6);
    }

    @Test
    void acceptsASingleAlertObject() throws Exception {
        ObjectNode single = alert("single-1", "001:A1", "002:B1", null);
        JsonNode response = ingest(json.writeValueAsString(single));
        assertThat(response.get("accepted").asInt()).isEqualTo(1);
    }

    @Test
    void duplicateWithinOneBatchIsCountedOnce() throws Exception {
        JsonNode response = ingest(alert("dup-1", "001:A1", "002:B1", null), alert("dup-1", "001:A1", "002:B1", null));
        assertThat(response.get("accepted").asInt()).isEqualTo(1);
        assertThat(response.get("duplicates").asInt()).isEqualTo(1);
    }

    @Test
    void invalidAlertIsRejectedWith400AndFieldErrors() throws Exception {
        ObjectNode bad = alert("bad-1", "001:A1", "002:B1", null);
        bad.remove("alertId");
        bad.put("score", 1.5);
        mvc.perform(post("/api/alerts").with(ENGINE).contentType(MediaType.APPLICATION_JSON)
                        .content("[" + json.writeValueAsString(bad) + "]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("[0].alertId"))))
                .andExpect(jsonPath("$.errors", hasItem(startsWith("[0].score"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert", Integer.class)).isZero();
    }

    @Test
    void invalidEdgeInsideDetectorIsRejected() throws Exception {
        ObjectNode bad = alert("bad-2", "001:A1", "002:B1", null);
        ((ObjectNode) bad.get("detectors").get(0).get("edges").get(0)).remove("from");
        mvc.perform(post("/api/alerts").with(ENGINE).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]", startsWith("[0].detectors[0].edges[0].from")));
    }

    @Test
    void malformedJsonAndEmptyBatchAreRejected() throws Exception {
        mvc.perform(post("/api/alerts").with(ENGINE).contentType(MediaType.APPLICATION_JSON).content("[{\"alertId\":"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/alerts").with(ENGINE).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/alerts").with(ENGINE).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"alertId\":\"x\",\"timestamp\":\"not-a-date\"}]"))
                .andExpect(status().isBadRequest());
    }
}
