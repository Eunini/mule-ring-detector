package io.github.eunini.mrd.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseGroupingIT extends IntegrationTestBase {

    @Test
    void alertSharingAnAccountJoinsTheOpenCase() throws Exception {
        long caseId = caseIdOf(ingest(alert("a-1", "001:A", "002:B", null)), 0);
        JsonNode second = ingest(alert("a-2", "002:B", "003:C", null));

        assertThat(caseIdOf(second, 0)).isEqualTo(caseId);
        JsonNode detail = getJson("/api/cases/" + caseId, ANALYST1);
        assertThat(detail.get("alertCount").asInt()).isEqualTo(2);
        assertThat(detail.get("accounts")).hasSize(3);
        assertThat(detail.get("totalAmountUsd").decimalValue()).isEqualByComparingTo("2001.00");
        assertThat(auditActions(caseId)).containsExactly("CASE_CREATED", "ALERT_ATTACHED", "ALERT_ATTACHED");
    }

    @Test
    void alertWithSameRingIdJoinsEvenWithoutSharedAccounts() throws Exception {
        long caseId = caseIdOf(ingest(alert("r-1", "001:A", "002:B", "R-9")), 0);
        long other = caseIdOf(ingest(alert("r-2", "007:X", "008:Y", "R-9")), 0);
        assertThat(other).isEqualTo(caseId);
    }

    @Test
    void ringAccountsAreUsedForMatching() throws Exception {
        long caseId = caseIdOf(ingest(alert("m-1", "001:A", "002:B", null, "001:A", "002:B", "009:Z")), 0);
        long joined = caseIdOf(ingest(alert("m-2", "009:Z", "010:Q", null)), 0);
        assertThat(joined).isEqualTo(caseId);
    }

    @Test
    void alertBridgingTwoCasesMergesThemIntoTheOldest() throws Exception {
        long first = caseIdOf(ingest(alert("g-1", "001:A", "002:B", null)), 0);
        long second = caseIdOf(ingest(alert("g-2", "005:C", "006:D", null)), 0);
        assertThat(first).isNotEqualTo(second);

        JsonNode bridge = ingest(alert("g-3", "002:B", "005:C", null));

        assertThat(caseIdOf(bridge, 0)).isEqualTo(first);
        JsonNode survivor = getJson("/api/cases/" + first, ANALYST1);
        assertThat(survivor.get("alertCount").asInt()).isEqualTo(3);
        assertThat(survivor.get("alerts")).hasSize(3);
        assertThat(survivor.get("accounts")).hasSize(4);
        JsonNode absorbed = getJson("/api/cases/" + second, ANALYST1);
        assertThat(absorbed.get("status").asText()).isEqualTo("MERGED");
        assertThat(absorbed.get("mergedIntoId").asLong()).isEqualTo(first);
        assertThat(absorbed.get("alerts")).isEmpty();

        assertThat(auditActions(first)).containsExactly("CASE_CREATED", "ALERT_ATTACHED", "MERGED", "ALERT_ATTACHED");
        assertThat(auditActions(second)).containsExactly("CASE_CREATED", "ALERT_ATTACHED", "MERGED");
        JsonNode mergeEvent = getJson("/api/cases/" + second + "/audit", ANALYST1).get(2);
        assertThat(mergeEvent.get("toStatus").asText()).isEqualTo("MERGED");
        assertThat(mergeEvent.get("actor").asText()).isEqualTo("engine");
    }

    @Test
    void closedCaseIsNotReopenedByNewAlert() throws Exception {
        long caseId = caseIdOf(ingest(alert("c-1", "001:A", "002:B", null)), 0);
        transition(caseId, ANALYST1, "INVESTIGATING", null).andExpect(status().isOk());
        transition(caseId, ANALYST1, "CLOSED", "FALSE_POSITIVE").andExpect(status().isOk());

        long next = caseIdOf(ingest(alert("c-2", "002:B", "003:C", null)), 0);

        assertThat(next).isNotEqualTo(caseId);
        assertThat(getJson("/api/cases/" + caseId, ANALYST1).get("alertCount").asInt()).isEqualTo(1);
    }

    @Test
    void caseListFiltersByStatusAndPages() throws Exception {
        ingest(fixture());
        ingest(alert("p-1", "001:A", "002:B", null));
        long investigating = caseIdOf(ingest(alert("p-2", "005:C", "006:D", null)), 0);
        transition(investigating, ANALYST1, "INVESTIGATING", null).andExpect(status().isOk());

        mvc.perform(get("/api/cases").with(ANALYST1).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/cases").with(ANALYST1).param("status", "INVESTIGATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(investigating));
        mvc.perform(get("/api/cases").with(ANALYST1).param("status", "BOGUS"))
                .andExpect(status().isBadRequest());

        JsonNode page = getJson("/api/cases?status=OPEN&sort=maxScore,desc", ANALYST1);
        List<Double> scores = new java.util.ArrayList<>();
        page.get("content").forEach(c -> scores.add(c.get("maxScore").asDouble()));
        assertThat(scores).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(page.get("content").get(0).get("priority").asText()).isEqualTo("CRITICAL");
    }
}
