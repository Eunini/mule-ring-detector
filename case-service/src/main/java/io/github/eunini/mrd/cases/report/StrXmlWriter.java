package io.github.eunini.mrd.cases.report;

import io.github.eunini.mrd.cases.config.StrProperties;
import io.github.eunini.mrd.cases.config.UserDirectory.UserProfile;
import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.service.AccountRef;
import java.io.ByteArrayOutputStream;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

/**
 * Writes a Suspicious Transaction Report with StAX.
 *
 * <p>Generic goAML-style structure for demonstration; not certified against any FIU schema.
 * Element names follow the conventions of goAML, the UNODC system used by financial
 * intelligence units in many countries. The output validates against this project's own
 * subset schema {@code classpath:str/str-subset.xsd}.
 */
@Component
public class StrXmlWriter {

    public static final String DISCLAIMER =
            "Generic goAML-style structure for demonstration; not certified against any FIU schema.";

    private static final DateTimeFormatter GOAML_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final XMLOutputFactory factory = XMLOutputFactory.newFactory();

    /** Input for one report. Alerts are expected in chronological order. */
    public record StrInput(CaseEntity caseEntity, List<AlertEntity> alerts, Set<String> detectors,
                           UserProfile reportingPerson, Instant submissionDate) {
    }

    public byte[] write(StrInput input, StrProperties props) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Xml w = new Xml(factory.createXMLStreamWriter(out, StandardCharsets.UTF_8.name()));
            w.raw().writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            w.raw().writeCharacters("\n");
            w.raw().writeComment(" " + DISCLAIMER + " ");
            w.raw().writeCharacters("\n");
            w.raw().writeComment(" Indicator, transmode and funds codes are project-specific demonstration values. ");
            w.open("report");

            w.leaf("rentity_id", props.rentityId());
            w.leaf("submission_code", "E");
            w.leaf("report_code", "STR");
            w.leaf("entity_reference", input.caseEntity().getReference());
            w.leaf("submission_date", GOAML_DATE.format(input.submissionDate()));
            w.leaf("currency_code_local", props.currencyCodeLocal());

            writeReportingPerson(w, input.reportingPerson());
            writeLocation(w, props.location());
            w.leaf("reason", reason(input));
            w.leaf("action", props.action());

            Set<Long> written = new LinkedHashSet<>();
            for (AlertEntity alert : input.alerts()) {
                if (written.add(alert.getTxId())) {
                    writeTransaction(w, alert, props);
                }
            }

            w.open("report_indicators");
            for (DetectorIndicators.Indicator indicator : indicators(input.detectors())) {
                w.leaf("indicator", indicator.code());
            }
            w.close();

            w.close();
            w.raw().writeCharacters("\n");
            w.raw().writeEndDocument();
            w.raw().flush();
            w.raw().close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("failed to write STR XML", e);
        }
        return out.toByteArray();
    }

    static List<DetectorIndicators.Indicator> indicators(Set<String> detectors) {
        Map<String, DetectorIndicators.Indicator> byCode = new LinkedHashMap<>();
        for (String detector : detectors) {
            DetectorIndicators.Indicator indicator = DetectorIndicators.forDetector(detector);
            byCode.putIfAbsent(indicator.code(), indicator);
        }
        byCode.putIfAbsent(DetectorIndicators.MODEL_SCORE.code(), DetectorIndicators.MODEL_SCORE);
        return new ArrayList<>(byCode.values());
    }

    private static void writeReportingPerson(Xml w, UserProfile person) throws XMLStreamException {
        String fullName = person.fullName() == null ? person.username() : person.fullName();
        int space = fullName.lastIndexOf(' ');
        String first = space > 0 ? fullName.substring(0, space) : fullName;
        String last = space > 0 ? fullName.substring(space + 1) : person.username();
        w.open("reporting_person");
        w.leaf("first_name", first);
        w.leaf("last_name", last);
        w.leaf("occupation", person.hasRole("SUPERVISOR") ? "Compliance Supervisor" : "Compliance Analyst");
        if (person.email() != null && !person.email().isBlank()) {
            w.leaf("email", person.email());
        }
        w.leaf("user_id", person.username());
        w.close();
    }

    private static void writeLocation(Xml w, StrProperties.Location location) throws XMLStreamException {
        w.open("location");
        w.leaf("address_type", location.addressType());
        w.leaf("address", location.address());
        w.leaf("city", location.city());
        w.leaf("country_code", location.countryCode());
        w.close();
    }

    private static void writeTransaction(Xml w, AlertEntity alert, StrProperties props)
            throws XMLStreamException {
        w.open("transaction");
        w.leaf("transactionnumber", String.valueOf(alert.getTxId()));
        w.leaf("internal_ref_number", alert.getAlertId());
        w.leaf("transaction_location", props.transactionLocation());
        w.leaf("date_transaction", GOAML_DATE.format(alert.getEventTs()));
        w.leaf("transmode_code", PaymentCodes.transmode(alert.getPaymentFormat()));
        w.leaf("amount_local", alert.getAmountUsd().setScale(2, RoundingMode.HALF_UP).toPlainString());

        String fundsCode = PaymentCodes.fundsCode(alert.getPaymentFormat());
        w.open("t_from");
        w.leaf("from_funds_code", fundsCode);
        writeAccount(w, "from_account", AccountRef.parse(alert.getFromAccount()));
        w.close();

        w.open("t_to");
        w.leaf("to_funds_code", fundsCode);
        writeAccount(w, "to_account", AccountRef.parse(alert.getToAccount()));
        w.close();

        w.leaf("comments", String.format(Locale.ROOT,
                "Original currency %s (%s); payment format %s; model %s score %.3f%s",
                alert.getCurrency() == null ? "n/a" : alert.getCurrency(),
                PaymentCodes.currencyCode(alert.getCurrency()),
                alert.getPaymentFormat() == null ? "n/a" : alert.getPaymentFormat(),
                alert.getModelVersion() == null ? "n/a" : alert.getModelVersion(),
                alert.getScore(),
                alert.getDetectorNames() == null || alert.getDetectorNames().isBlank()
                        ? "" : "; detectors " + alert.getDetectorNames()));
        w.close();
    }

    private static void writeAccount(Xml w, String name, AccountRef ref) throws XMLStreamException {
        w.open(name);
        w.leaf("institution_name", "Bank " + ref.bankId());
        w.leaf("institution_code", ref.bankId());
        w.leaf("account", ref.account());
        w.close();
    }

    private static String reason(StrInput input) {
        CaseEntity c = input.caseEntity();
        Set<String> models = new LinkedHashSet<>();
        input.alerts().forEach(a -> {
            if (a.getModelVersion() != null) {
                models.add(a.getModelVersion());
            }
        });
        List<String> typologies = input.detectors().stream()
                .map(d -> DetectorIndicators.forDetector(d).description().toLowerCase(Locale.ROOT)).distinct().toList();
        return String.format(Locale.ROOT,
                "Case %s: %d transaction alert(s) across %d account(s) totalling USD %s were flagged by the "
                        + "network monitoring model%s (maximum score %.3f). Observed typologies: %s. The pattern is "
                        + "consistent with money-mule activity and layering of illicit funds.",
                c.getReference(), c.getAlertCount(), c.getAccounts().size(),
                c.getTotalAmountUsd().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                models.isEmpty() ? "" : " " + String.join(", ", models),
                c.getMaxScore(),
                typologies.isEmpty() ? "model score only" : String.join("; ", typologies));
    }

    /** Minimal pretty-printing wrapper: one element per line, two-space indentation. */
    private static final class Xml {
        private final XMLStreamWriter w;
        private int depth;

        Xml(XMLStreamWriter w) {
            this.w = w;
        }

        XMLStreamWriter raw() {
            return w;
        }

        void open(String name) throws XMLStreamException {
            newline();
            w.writeStartElement(name);
            depth++;
        }

        void close() throws XMLStreamException {
            depth--;
            newline();
            w.writeEndElement();
        }

        void leaf(String name, String value) throws XMLStreamException {
            newline();
            w.writeStartElement(name);
            w.writeCharacters(value == null ? "" : value);
            w.writeEndElement();
        }

        private void newline() throws XMLStreamException {
            w.writeCharacters("\n" + "  ".repeat(depth));
        }
    }
}
