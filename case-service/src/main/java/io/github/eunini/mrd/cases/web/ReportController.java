package io.github.eunini.mrd.cases.web;

import io.github.eunini.mrd.cases.service.CaseQueryService;
import io.github.eunini.mrd.cases.service.ReportService;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report downloads. STR XML and PDF generation are recorded (report metadata and audit
 * event); the HTML summary is a read-only view.
 */
@RestController
@RequestMapping("/api/cases/{id}")
public class ReportController {

    private final ReportService reports;
    private final CaseQueryService queries;

    public ReportController(ReportService reports, CaseQueryService queries) {
        this.reports = reports;
        this.queries = queries;
    }

    @GetMapping(value = "/str.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> strXml(@PathVariable Long id, Principal principal) {
        byte[] xml = reports.strXml(id, principal.getName());
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, inline(id, "str", "xml"))
                .body(xml);
    }

    @GetMapping(value = "/summary.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> summaryHtml(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(reports.summaryHtml(id, principal.getName()));
    }

    @GetMapping(value = "/summary.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> summaryPdf(@PathVariable Long id, Principal principal) {
        byte[] pdf = reports.summaryPdf(id, principal.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, inline(id, "summary", "pdf"))
                .body(pdf);
    }

    private String inline(Long id, String kind, String ext) {
        String reference = queries.require(id).getReference();
        return ContentDisposition.inline().filename(reference + "-" + kind + "." + ext).build().toString();
    }
}
