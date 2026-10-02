package io.github.eunini.mrd.cases.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "investigation_case")
public class CaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference", unique = true)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition")
    private Disposition disposition;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private Priority priority;

    @Column(name = "max_score", nullable = false)
    private double maxScore;

    @Column(name = "alert_count", nullable = false)
    private int alertCount;

    @Column(name = "total_amount_usd", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmountUsd;

    @Column(name = "assignee")
    private String assignee;

    @Column(name = "merged_into_id")
    private Long mergedIntoId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "case_account", joinColumns = @JoinColumn(name = "case_id"))
    @Column(name = "account_id", nullable = false)
    @BatchSize(size = 50)
    private Set<String> accounts = new TreeSet<>();

    protected CaseEntity() {
    }

    public CaseEntity(Instant now) {
        this.status = CaseStatus.OPEN;
        this.priority = Priority.LOW;
        this.maxScore = 0.0;
        this.alertCount = 0;
        this.totalAmountUsd = BigDecimal.ZERO;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Deterministic reference built from the creation year and the database id. */
    public void assignReference() {
        if (reference == null && id != null) {
            int year = createdAt.atZone(ZoneOffset.UTC).getYear();
            reference = String.format("CASE-%d-%06d", year, id);
        }
    }

    public void recordAlert(double score, BigDecimal amountUsd, Instant now) {
        maxScore = Math.max(maxScore, score);
        alertCount++;
        totalAmountUsd = totalAmountUsd.add(amountUsd);
        refreshPriority();
        touch(now);
    }

    public void absorb(CaseEntity other, Instant now) {
        accounts.addAll(other.accounts);
        maxScore = Math.max(maxScore, other.maxScore);
        alertCount += other.alertCount;
        totalAmountUsd = totalAmountUsd.add(other.totalAmountUsd);
        refreshPriority();
        touch(now);
    }

    public void markMergedInto(CaseEntity survivor, Instant now) {
        this.status = CaseStatus.MERGED;
        this.mergedIntoId = survivor.getId();
        this.closedAt = now;
        touch(now);
    }

    public void changeStatus(CaseStatus to, Disposition disposition, Instant now) {
        this.status = to;
        this.disposition = disposition;
        this.closedAt = to == CaseStatus.CLOSED ? now : null;
        touch(now);
    }

    public void assignTo(String assignee, Instant now) {
        this.assignee = assignee;
        touch(now);
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    private void refreshPriority() {
        this.priority = Priority.of(maxScore, alertCount, totalAmountUsd);
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public CaseStatus getStatus() { return status; }
    public Disposition getDisposition() { return disposition; }
    public Priority getPriority() { return priority; }
    public double getMaxScore() { return maxScore; }
    public int getAlertCount() { return alertCount; }
    public BigDecimal getTotalAmountUsd() { return totalAmountUsd; }
    public String getAssignee() { return assignee; }
    public Long getMergedIntoId() { return mergedIntoId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Set<String> getAccounts() { return accounts; }
}
