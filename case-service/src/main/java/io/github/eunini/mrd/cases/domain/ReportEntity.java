package io.github.eunini.mrd.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Metadata of a generated report (content is regenerated on demand, the hash pins it). */
@Entity
@Immutable
@Table(name = "report")
public class ReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, updatable = false)
    private ReportType reportType;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "entity_reference", nullable = false, updatable = false)
    private String entityReference;

    @Column(name = "sha256", nullable = false, updatable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "generated_by", nullable = false, updatable = false)
    private String generatedBy;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    protected ReportEntity() {
    }

    public ReportEntity(Long caseId, ReportType reportType, String contentType, String entityReference,
                        String sha256, long sizeBytes, String generatedBy, Instant generatedAt) {
        this.caseId = caseId;
        this.reportType = reportType;
        this.contentType = contentType;
        this.entityReference = entityReference;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.generatedBy = generatedBy;
        this.generatedAt = generatedAt;
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public ReportType getReportType() { return reportType; }
    public String getContentType() { return contentType; }
    public String getEntityReference() { return entityReference; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public String getGeneratedBy() { return generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
}
