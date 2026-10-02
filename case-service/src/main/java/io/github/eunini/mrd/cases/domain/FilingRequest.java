package io.github.eunini.mrd.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** A request to file an STR, decided by a second person (four-eyes principle). */
@Entity
@Table(name = "filing_request")
public class FilingRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "report_id", nullable = false, updatable = false)
    private Long reportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FilingStatus status;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "request_comment", updatable = false)
    private String requestComment;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_comment")
    private String decisionComment;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FilingRequest() {
    }

    public FilingRequest(Long caseId, Long reportId, String requestedBy, Instant requestedAt, String requestComment) {
        this.caseId = caseId;
        this.reportId = reportId;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.requestComment = requestComment;
        this.status = FilingStatus.PENDING;
    }

    public void decide(FilingStatus outcome, String decidedBy, Instant decidedAt, String comment) {
        if (status != FilingStatus.PENDING) {
            throw new IllegalStateException("filing request already decided");
        }
        this.status = outcome;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionComment = comment;
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public Long getReportId() { return reportId; }
    public FilingStatus getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getRequestComment() { return requestComment; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionComment() { return decisionComment; }
}
