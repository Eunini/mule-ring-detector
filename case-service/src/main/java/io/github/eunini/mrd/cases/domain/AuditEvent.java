package io.github.eunini.mrd.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Append-only audit record. The entity is immutable for Hibernate, every column is
 * non-updatable, lifecycle callbacks reject updates and deletes, and the repository
 * exposes no mutating operations other than insert.
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    public static final int MAX_DETAILS = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private CaseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", updatable = false)
    private CaseStatus toStatus;

    @Column(name = "details", updatable = false, length = MAX_DETAILS)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(Long caseId, String actor, AuditAction action, CaseStatus fromStatus,
                      CaseStatus toStatus, String details, Instant createdAt) {
        this.caseId = caseId;
        this.actor = actor;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.details = details == null || details.length() <= MAX_DETAILS
                ? details : details.substring(0, MAX_DETAILS);
        this.createdAt = createdAt;
    }

    @PreUpdate
    void rejectUpdate() {
        throw new IllegalStateException("audit events are append-only");
    }

    @PreRemove
    void rejectRemove() {
        throw new IllegalStateException("audit events are append-only");
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public String getActor() { return actor; }
    public AuditAction getAction() { return action; }
    public CaseStatus getFromStatus() { return fromStatus; }
    public CaseStatus getToStatus() { return toStatus; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
