package io.github.eunini.mrd.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FourEyesFilingIT extends IntegrationTestBase {

    private long caseId;

    @BeforeEach
    void escalatedCase() throws Exception {
        caseId = caseIdOf(ingest(fixture()), 0);
        escalate(caseId);
    }

    private void generateStr() throws Exception {
        mvc.perform(get("/api/cases/" + caseId + "/str.xml").with(ANALYST1)).andExpect(status().isOk());
    }

    private String path(String suffix) {
        return "/api/cases/" + caseId + "/" + suffix;
    }

    @Test
    void filingRequestNeedsAnEscalatedCaseAndAnStrReport() throws Exception {
        postJson(path("filing-request"), ANALYST1, Map.of()).andExpect(status().isConflict());

        transition(caseId, ANALYST1, "INVESTIGATING", null).andExpect(status().isOk());
        generateStr();
        postJson(path("filing-request"), ANALYST1, Map.of()).andExpect(status().isConflict());

        transition(caseId, ANALYST1, "ESCALATED", null).andExpect(status().isOk());
        postJson(path("filing-request"), ANALYST1, Map.of("comment", "Ring confirmed by branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filingRequest.status").value("PENDING"))
                .andExpect(jsonPath("$.filingRequest.requestedBy").value("analyst1"));
        postJson(path("filing-request"), ANALYST2, Map.of()).andExpect(status().isConflict());
    }

    @Test
    void supervisorApprovalByASecondPersonClosesWithStrFiled() throws Exception {
        generateStr();
        postJson(path("filing-request"), ANALYST1, Map.of("comment", "file it")).andExpect(status().isOk());

        postJson(path("filing-approval"), SUPERVISOR1, Map.of("comment", "approved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.disposition").value("STR_FILED"))
                .andExpect(jsonPath("$.filingRequest.status").value("APPROVED"))
                .andExpect(jsonPath("$.filingRequest.decidedBy").value("supervisor1"));

        assertThat(auditActions(caseId)).containsSubsequence(
                "CASE_CREATED", "ALERT_ATTACHED", "TRANSITION", "TRANSITION",
                "REPORT_GENERATED", "FILING_REQUESTED", "FILING_APPROVED");
        JsonNode audit = getJson(path("audit"), ANALYST1);
        JsonNode last = audit.get(audit.size() - 1);
        assertThat(last.get("action").asText()).isEqualTo("FILING_APPROVED");
        assertThat(last.get("actor").asText()).isEqualTo("supervisor1");
        assertThat(last.get("fromStatus").asText()).isEqualTo("ESCALATED");
        assertThat(last.get("toStatus").asText()).isEqualTo("CLOSED");
        JsonNode requested = audit.get(audit.size() - 2);
        assertThat(requested.get("actor").asText()).isEqualTo("analyst1");
        assertThat(requested.get("id").asLong()).isLessThan(last.get("id").asLong());

        postJson(path("filing-approval"), SUPERVISOR1, Map.of()).andExpect(status().isConflict());
    }

    @Test
    void requesterCannotApproveTheirOwnFiling() throws Exception {
        generateStr();
        postJson(path("filing-request"), SUPERVISOR1, Map.of()).andExpect(status().isOk());

        postJson(path("filing-approval"), SUPERVISOR1, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("four-eyes")));
        postJson(path("filing-rejection"), SUPERVISOR1, Map.of("reason", "self"))
                .andExpect(status().isForbidden());
        assertThat(getJson("/api/cases/" + caseId, ANALYST1).get("status").asText()).isEqualTo("ESCALATED");
        assertThat(auditActions(caseId)).doesNotContain("FILING_APPROVED", "FILING_REJECTED");
    }

    @Test
    void analystCannotApproveOrReject() throws Exception {
        generateStr();
        postJson(path("filing-request"), ANALYST1, Map.of()).andExpect(status().isOk());
        postJson(path("filing-approval"), ANALYST2, Map.of()).andExpect(status().isForbidden());
        postJson(path("filing-rejection"), ANALYST2, Map.of("reason", "x")).andExpect(status().isForbidden());
        assertThat(getJson("/api/cases/" + caseId, ANALYST1).at("/filingRequest/status").asText()).isEqualTo("PENDING");
    }

    @Test
    void rejectionSendsTheCaseBackToInvestigation() throws Exception {
        generateStr();
        postJson(path("filing-request"), ANALYST1, Map.of()).andExpect(status().isOk());
        postJson(path("filing-rejection"), SUPERVISOR1, Map.of()).andExpect(status().isBadRequest());

        postJson(path("filing-rejection"), SUPERVISOR1, Map.of("reason", "Need counterparty KYC first"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.filingRequest.status").value("REJECTED"))
                .andExpect(jsonPath("$.filingRequest.decisionComment").value("Need counterparty KYC first"));

        JsonNode audit = getJson(path("audit"), ANALYST1);
        JsonNode last = audit.get(audit.size() - 1);
        assertThat(last.get("action").asText()).isEqualTo("FILING_REJECTED");
        assertThat(last.get("toStatus").asText()).isEqualTo("INVESTIGATING");
    }

    @Test
    void manualTransitionsAreBlockedWhileFilingIsPending() throws Exception {
        generateStr();
        postJson(path("filing-request"), ANALYST1, Map.of()).andExpect(status().isOk());
        transition(caseId, ANALYST2, "INVESTIGATING", null).andExpect(status().isConflict());
        transition(caseId, ANALYST2, "CLOSED", "NO_FURTHER_ACTION").andExpect(status().isConflict());
    }

    @Test
    void approvalWithoutPendingRequestConflicts() throws Exception {
        postJson(path("filing-approval"), SUPERVISOR1, Map.of()).andExpect(status().isConflict());
    }
}
