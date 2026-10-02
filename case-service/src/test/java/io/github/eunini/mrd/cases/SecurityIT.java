package io.github.eunini.mrd.cases;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SecurityIT extends IntegrationTestBase {

    @Test
    void anonymousApiCallsAreChallenged() throws Exception {
        mvc.perform(get("/api/cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uiRequestsGetA401WithoutBrowserChallenge() throws Exception {
        mvc.perform(get("/api/me").header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(get("/api/cases").with(httpBasic("analyst1", "wrong"))).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheIngestRoleMayPostAlerts() throws Exception {
        String body = json.writeValueAsString(alert("s-1", "001:A", "002:B", null));
        mvc.perform(post("/api/alerts").with(ANALYST1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alerts").with(SUPERVISOR1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alerts").with(ENGINE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void engineCannotReadOrWorkCases() throws Exception {
        mvc.perform(get("/api/cases").with(ENGINE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/cases/1/transition").with(ENGINE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"INVESTIGATING\"}")).andExpect(status().isForbidden());
    }

    @Test
    void supervisorInheritsAnalystAccess() throws Exception {
        mvc.perform(get("/api/me").with(SUPERVISOR1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("supervisor1"))
                .andExpect(jsonPath("$.roles[0]").value("SUPERVISOR"));
        mvc.perform(get("/api/cases").with(SUPERVISOR1)).andExpect(status().isOk());
    }

    @Test
    void staticUiAndHealthArePublic() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
