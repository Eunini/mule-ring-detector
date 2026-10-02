package io.github.eunini.mrd.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

class ReportsIT extends IntegrationTestBase {

    private long fanOutCase;
    private long cycleCase;

    @BeforeEach
    void ingestFixture() throws Exception {
        JsonNode response = ingest(fixture());
        fanOutCase = caseIdOf(response, 0);
        cycleCase = caseIdOf(response, 3);
    }

    @Test
    void strXmlValidatesAgainstTheSubsetSchemaAndIsRecorded() throws Exception {
        byte[] xml = mvc.perform(get("/api/cases/" + cycleCase + "/str.xml").with(ANALYST2))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("-str.xml")))
                .andReturn().getResponse().getContentAsByteArray();

        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(getClass().getResource("/str/str-subset.xsd"))
                .newValidator()
                .validate(new StreamSource(new ByteArrayInputStream(xml)));

        String text = new String(xml, StandardCharsets.UTF_8);
        assertThat(text).contains("not certified against any FIU schema");

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        String reference = getJson("/api/cases/" + cycleCase, ANALYST1).get("reference").asText();
        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("report");
        assertThat(first(doc, "entity_reference")).isEqualTo(reference);
        assertThat(first(doc, "report_code")).isEqualTo("STR");
        assertThat(first(doc, "submission_code")).isEqualTo("E");
        assertThat(first(doc, "rentity_id")).isEqualTo("DEMO-FIU-0001");
        assertThat(first(doc, "user_id")).isEqualTo("analyst2");
        assertThat(doc.getElementsByTagName("transaction").getLength()).isEqualTo(3);
        assertThat(all(doc, "transactionnumber")).containsExactly("200001", "200005", "200009");
        assertThat(all(doc, "indicator")).contains("MRD-CYCLE", "MRD-PASSTHRU", "MRD-RING", "MRD-MODEL");
        assertThat(all(doc, "institution_code")).contains("020", "0240229");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report WHERE case_id = ? AND report_type = 'STR_XML'",
                Integer.class, cycleCase)).isEqualTo(1);
        assertThat(auditActions(cycleCase)).last().isEqualTo("REPORT_GENERATED");
        JsonNode detail = getJson("/api/cases/" + cycleCase, ANALYST1);
        assertThat(detail.get("reports").get(0).get("sha256").asText()).hasSize(64);
        assertThat(detail.get("reports").get(0).get("sizeBytes").asLong()).isEqualTo(xml.length);
    }

    @Test
    void summaryHtmlContainsCaseHeaderEvidenceAndSvgGraph() throws Exception {
        String html = mvc.perform(get("/api/cases/" + fanOutCase + "/summary.html").with(ANALYST1))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();
        String reference = getJson("/api/cases/" + fanOutCase, ANALYST1).get("reference").asText();

        assertThat(html).contains(reference).contains("<svg").contains("</svg>").contains("<circle")
                .contains("<line").contains("fan_out").contains("MRD-FANOUT").contains("Audit trail")
                .contains("021174:800737690");
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void summaryPdfIsRenderedAndRecorded() throws Exception {
        byte[] pdf = mvc.perform(get("/api/cases/" + fanOutCase + "/summary.pdf").with(ANALYST1))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(2_000);
        assertThat(auditActions(fanOutCase)).last().isEqualTo("REPORT_GENERATED");
    }

    @Test
    void graphEndpointReturnsNodesAndEdgesFromEvidence() throws Exception {
        JsonNode graph = getJson("/api/cases/" + fanOutCase + "/graph", ANALYST1);
        List<String> ids = new ArrayList<>();
        graph.get("nodes").forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).hasSize(9).contains("021174:800737690", "01601:800578800");

        JsonNode source = null;
        for (JsonNode n : graph.get("nodes")) {
            if (n.get("id").asText().equals("021174:800737690")) {
                source = n;
            }
        }
        assertThat(source).isNotNull();
        assertThat(source.get("role").asText()).isEqualTo("source");
        assertThat(source.get("label").asText()).isEqualTo("800737690 @021174");

        assertThat(graph.get("edges")).hasSize(8);
        JsonNode alerted = null;
        for (JsonNode e : graph.get("edges")) {
            if (e.get("txId").asLong() == 100007) {
                alerted = e;
            }
        }
        assertThat(alerted).isNotNull();
        assertThat(alerted.get("alerted").asBoolean()).isTrue();
        assertThat(alerted.get("detector").asText()).contains("alert").contains("fan_out");
        assertThat(alerted.get("laundering").asBoolean()).isTrue();
        assertThat(alerted.get("source").asText()).isEqualTo("021174:800737690");
    }

    @Test
    void reportsForUnknownCaseReturn404() throws Exception {
        mvc.perform(get("/api/cases/424242/str.xml").with(ANALYST1)).andExpect(status().isNotFound());
        mvc.perform(get("/api/cases/424242/summary.html").with(ANALYST1)).andExpect(status().isNotFound());
        mvc.perform(get("/api/cases/424242/graph").with(ANALYST1)).andExpect(status().isNotFound());
    }

    private static String first(Document doc, String tag) {
        return doc.getElementsByTagName(tag).item(0).getTextContent();
    }

    private static List<String> all(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(nodes.item(i).getTextContent());
        }
        return values;
    }
}
