package io.github.eunini.mrd.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowIT extends IntegrationTestBase {

    private long caseId;

    @BeforeEach
    void openCase() throws Exception {
        caseId = caseIdOf(ingest(alert("w-1", "001:A", "002:B", null)), 0);
    }

    @Test
    void legalPathWithSendBackEndsClosedAndIsFullyAudited() throws Exception {
        transition(caseId, ANALYST1, "INVESTIGATING", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));
        transition(caseId, ANALYST1, "ESCALATED", null).andExpect(status().isOk());
        transition(caseId, SUPERVISOR1, "INVESTIGATING", null).andExpect(status().isOk());
        transition(caseId, ANALYST2, "CLOSED", "NO_FURTHER_ACTION").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.disposition").value("NO_FURTHER_ACTION"))
                .andExpect(jsonPath("$.closedAt").exists())
                .andExpect(jsonPath("$.allowedTransitions.length()").value(0));

        JsonNode audit = getJson("/api/cases/" + caseId + "/audit", ANALYST1);
        List<String> moves = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        audit.forEach(e -> {
            ids.add(e.get("id").asLong());
            if (e.get("action").asText().equals("TRANSITION")) {
                moves.add(e.get("actor").asText() + ":" + e.get("fromStatus").asText() + ">" + e.get("toStatus").asText());
            }
        });
        assertThat(moves).containsExactly("analyst1:OPEN>INVESTIGATING", "analyst1:INVESTIGATING>ESCALATED",
                "supervisor1:ESCALATED>INVESTIGATING", "analyst2:INVESTIGATING>CLOSED");
        assertThat(ids).isSorted();
        assertThat(audit.get(audit.size() - 1).get("details").asText()).contains("NO_FURTHER_ACTION");
    }

    @Test
    void skippingInvestigationIsIllegal() throws Exception {
        transition(caseId, ANALYST1, "ESCALATED", null).andExpect(status().isConflict());
        transition(caseId, ANALYST1, "CLOSED", "FALSE_POSITIVE").andExpect(status().isConflict());
        assertThat(getJson("/api/cases/" + caseId, ANALYST1).get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void closedCaseIsTerminal() throws Exception {
        transition(caseId, ANALYST1, "INVESTIGATING", null).andExpect(status().isOk());
        transition(caseId, ANALYST1, "CLOSED", "FALSE_POSITIVE").andExpect(status().isOk());
        transition(caseId, ANALYST1, "INVESTIGATING", null).andExpect(status().isConflict());
        transition(caseId, ANALYST1, "OPEN", null).andExpect(status().isConflict());
        postJson("/api/cases/" + caseId + "/assign", ANALYST1, Map.of("assignee", "analyst2"))
                .andExpect(status().isConflict());
    }

    @Test
    void closingRequiresANonFilingDisposition() throws Exception {
        transition(caseId, ANALYST1, "INVESTIGATING", null).andExpect(status().isOk());
        postJson("/api/cases/" + caseId + "/transition", ANALYST1, Map.of("to", "CLOSED"))
                .andExpect(status().isBadRequest());
        transition(caseId, ANALYST1, "CLOSED", "STR_FILED").andExpect(status().isConflict());
        transition(caseId, ANALYST1, "ESCALATED", null).andExpect(status().isOk());
        transition(caseId, ANALYST1, "CLOSED", "STR_FILED").andExpect(status().isConflict());
        postJson("/api/cases/" + caseId + "/transition", ANALYST1,
                Map.of("to", "INVESTIGATING", "disposition", "FALSE_POSITIVE"))
                .andExpect(status().isBadRequest());
        postJson("/api/cases/" + caseId + "/transition", ANALYST1, Map.of("comment", "no target"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assignAndCommentAreAudited() throws Exception {
        postJson("/api/cases/" + caseId + "/assign", SUPERVISOR1, Map.of("assignee", "analyst2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value("analyst2"));
        postJson("/api/cases/" + caseId + "/assign", ANALYST1, Map.of("assignee", "engine"))
                .andExpect(status().isBadRequest());
        postJson("/api/cases/" + caseId + "/comments", ANALYST2, Map.of("text", "Called the branch <b>today</b>"))
                .andExpect(status().isOk());
        postJson("/api/cases/" + caseId + "/comments", ANALYST2, Map.of("text", " "))
                .andExpect(status().isBadRequest());

        JsonNode audit = getJson("/api/cases/" + caseId + "/audit", ANALYST1);
        JsonNode assigned = audit.get(2);
        JsonNode comment = audit.get(3);
        assertThat(assigned.get("action").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.get("actor").asText()).isEqualTo("supervisor1");
        assertThat(comment.get("action").asText()).isEqualTo("COMMENT");
        assertThat(comment.get("actor").asText()).isEqualTo("analyst2");
        assertThat(comment.get("details").asText()).isEqualTo("Called the branch <b>today</b>");
    }

    @Test
    void unknownCaseReturns404() throws Exception {
        mvc.perform(get("/api/cases/999999").with(ANALYST1)).andExpect(status().isNotFound());
        transition(999999, ANALYST1, "INVESTIGATING", null).andExpect(status().isNotFound());
    }
}
