package io.github.eunini.mrd.cases.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eunini.mrd.cases.config.StrProperties;
import io.github.eunini.mrd.cases.config.UserDirectory.UserProfile;
import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class StrXmlWriterTest {

    private final StrProperties props = new StrProperties("DEMO-FIU-0001", "Example Bank", "USD", null, null,
            new StrProperties.Location("B", "1 Example Street", "Example City", "XX"));

    @Test
    void writesParseableGoAmlStyleXmlThatValidatesAndEscapesText() throws Exception {
        Instant now = Instant.parse("2026-01-15T09:30:00Z");
        CaseEntity c = new CaseEntity(now);
        setId(c, 123L);
        c.assignReference();
        AlertEntity a1 = alert("txn-1", 1L, "021174:800737690", "012:80011F990", "2848.96", "Euro", "ACH", "fan_out");
        AlertEntity a2 = alert("txn-2", 2L, "012:80011F990", "noBankAccount", "100", "Bitcoin", "Bitcoin", "");
        c.recordAlert(0.93, a1.getAmountUsd(), now);
        c.recordAlert(0.81, a2.getAmountUsd(), now);
        UserProfile person = new UserProfile("analyst1", "Alex <Example> & Co", "a@example.org", List.of("ANALYST"));

        byte[] xml = new StrXmlWriter().write(new StrXmlWriter.StrInput(c, List.of(a1, a2, a1),
                new LinkedHashSet<>(List.of("fan_out", "velocity", "unknown_thing")), person, now), props);

        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(getClass().getResource("/str/str-subset.xsd"))
                .newValidator().validate(new StreamSource(new ByteArrayInputStream(xml)));

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        XPath x = XPathFactory.newInstance().newXPath();
        assertThat(x.evaluate("/report/entity_reference", doc)).isEqualTo("CASE-2026-000123");
        assertThat(x.evaluate("/report/rentity_id", doc)).isEqualTo("DEMO-FIU-0001");
        assertThat(x.evaluate("/report/submission_date", doc)).isEqualTo("2026-01-15T09:30:00");
        assertThat(x.evaluate("/report/reporting_person/first_name", doc)).isEqualTo("Alex <Example> &");
        assertThat(x.evaluate("count(/report/transaction)", doc)).isEqualTo("2");
        assertThat(x.evaluate("/report/transaction[1]/t_from/from_account/institution_code", doc)).isEqualTo("021174");
        assertThat(x.evaluate("/report/transaction[1]/t_from/from_account/account", doc)).isEqualTo("800737690");
        assertThat(x.evaluate("/report/transaction[1]/t_to/to_account/institution_code", doc)).isEqualTo("012");
        assertThat(x.evaluate("/report/transaction[1]/amount_local", doc)).isEqualTo("2848.96");
        assertThat(x.evaluate("/report/transaction[1]/transmode_code", doc)).isEqualTo("ACH");
        assertThat(x.evaluate("/report/transaction[2]/transmode_code", doc)).isEqualTo("VIRTUAL");
        assertThat(x.evaluate("/report/transaction[2]/t_to/to_account/institution_code", doc)).isEqualTo("UNKNOWN");
        assertThat(x.evaluate("/report/transaction[1]/comments", doc)).contains("EUR");
        assertThat(x.evaluate("/report/report_indicators/indicator[1]", doc)).isEqualTo("MRD-FANOUT");
        assertThat(x.evaluate("/report/report_indicators/indicator[2]", doc)).isEqualTo("MRD-VELOCITY");
        assertThat(x.evaluate("/report/report_indicators/indicator[3]", doc)).isEqualTo("MRD-OTHER");
        assertThat(x.evaluate("/report/report_indicators/indicator[4]", doc)).isEqualTo("MRD-MODEL");
        assertThat(new String(xml, java.nio.charset.StandardCharsets.UTF_8)).contains(StrXmlWriter.DISCLAIMER);
    }

    @Test
    void everyEngineDetectorHasADedicatedIndicator() {
        for (String detector : List.of("fan_out", "fan_in", "cycle", "scatter_gather", "gather_scatter",
                "pass_through", "velocity", "ring")) {
            assertThat(DetectorIndicators.forDetector(detector)).isNotEqualTo(DetectorIndicators.OTHER);
        }
        assertThat(DetectorIndicators.all().values().stream().map(DetectorIndicators.Indicator::code).distinct())
                .hasSize(8);
    }

    private static AlertEntity alert(String id, long tx, String from, String to, String amount, String currency,
                                     String format, String detectors) {
        return new AlertEntity(id, tx, Instant.parse("2022-09-01T00:06:00Z"), from, to, new BigDecimal(amount),
                currency, format, 0.9, 0.71, "gbdt-v1", "R-1", detectors, "{}", Instant.now());
    }

    private static void setId(CaseEntity c, long id) throws Exception {
        Field f = CaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(c, id);
    }
}
