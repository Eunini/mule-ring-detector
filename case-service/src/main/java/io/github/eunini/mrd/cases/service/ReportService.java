package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.config.StrProperties;
import io.github.eunini.mrd.cases.config.UserDirectory;
import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.domain.AuditAction;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.domain.ReportEntity;
import io.github.eunini.mrd.cases.domain.ReportType;
import io.github.eunini.mrd.cases.report.CaseSummaryRenderer;
import io.github.eunini.mrd.cases.report.PdfRenderer;
import io.github.eunini.mrd.cases.report.StrXmlWriter;
import io.github.eunini.mrd.cases.repository.ReportRepository;
import io.github.eunini.mrd.cases.web.dto.AlertPayload;
import io.github.eunini.mrd.cases.web.dto.GraphView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Produces STR XML, the HTML/PDF case summary and the evidence graph for a case. */
@Service
public class ReportService {

    private final CaseQueryService queries;
    private final GraphService graphs;
    private final EvidenceReader evidence;
    private final StrXmlWriter strWriter;
    private final CaseSummaryRenderer summaryRenderer;
    private final PdfRenderer pdfRenderer;
    private final ReportRepository reports;
    private final AuditService audit;
    private final UserDirectory users;
    private final StrProperties strProperties;
    private final Clock clock;

    public ReportService(CaseQueryService queries, GraphService graphs, EvidenceReader evidence,
                         StrXmlWriter strWriter, CaseSummaryRenderer summaryRenderer, PdfRenderer pdfRenderer,
                         ReportRepository reports, AuditService audit, UserDirectory users,
                         StrProperties strProperties, Clock clock) {
        this.queries = queries;
        this.graphs = graphs;
        this.evidence = evidence;
        this.strWriter = strWriter;
        this.summaryRenderer = summaryRenderer;
        this.pdfRenderer = pdfRenderer;
        this.reports = reports;
        this.audit = audit;
        this.users = users;
        this.strProperties = strProperties;
        this.clock = clock;
    }

    @Transactional
    public byte[] strXml(Long caseId, String actor) {
        CaseEntity c = queries.require(caseId);
        List<AlertEntity> alerts = queries.alerts(caseId);
        if (alerts.isEmpty()) {
            throw ApiException.conflict("case has no alerts to report");
        }
        Set<String> detectors = new LinkedHashSet<>();
        for (AlertEntity alert : alerts) {
            evidence.read(alert).detectorsOrEmpty().forEach(d -> detectors.add(d.name()));
        }
        Instant now = clock.instant();
        byte[] xml = strWriter.write(new StrXmlWriter.StrInput(c, alerts, detectors,
                users.profileOrDefault(actor), now), strProperties);
        record(c, ReportType.STR_XML, "application/xml", xml, actor, now);
        return xml;
    }

    @Transactional(readOnly = true)
    public GraphView graph(Long caseId) {
        queries.require(caseId);
        return graphs.build(queries.alerts(caseId));
    }

    @Transactional(readOnly = true)
    public String summaryHtml(Long caseId, String actor) {
        return renderSummary(queries.require(caseId), actor, clock.instant());
    }

    @Transactional
    public byte[] summaryPdf(Long caseId, String actor) {
        CaseEntity c = queries.require(caseId);
        Instant now = clock.instant();
        byte[] pdf = pdfRenderer.render(renderSummary(c, actor, now));
        record(c, ReportType.CASE_SUMMARY_PDF, "application/pdf", pdf, actor, now);
        return pdf;
    }

    private String renderSummary(CaseEntity c, String actor, Instant now) {
        List<AlertEntity> alerts = queries.alerts(c.getId());
        GraphView graph = graphs.build(alerts);
        Function<AlertEntity, AlertPayload> reader = evidence::read;
        return summaryRenderer.render(new CaseSummaryRenderer.SummaryInput(
                c, alerts, reader, graph, audit.trail(c.getId()), actor, now));
    }

    private void record(CaseEntity c, ReportType type, String contentType, byte[] content, String actor, Instant now) {
        String sha = sha256(content);
        ReportEntity report = reports.save(new ReportEntity(c.getId(), type, contentType, c.getReference(), sha,
                content.length, actor, now));
        audit.record(c, actor, AuditAction.REPORT_GENERATED, c.getStatus(), c.getStatus(),
                type + " report #" + report.getId() + " generated (" + content.length + " bytes, sha256 " + sha + ")");
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
