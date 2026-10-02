package io.github.eunini.mrd.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** Link between an alert and the case that currently owns it (moves on merge). */
@Entity
@Table(name = "case_alert")
public class CaseAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false)
    private CaseEntity caseEntity;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_pk", nullable = false, updatable = false, unique = true)
    private AlertEntity alert;

    @Column(name = "attached_at", nullable = false)
    private Instant attachedAt;

    protected CaseAlert() {
    }

    public CaseAlert(CaseEntity caseEntity, AlertEntity alert, Instant attachedAt) {
        this.caseEntity = caseEntity;
        this.alert = alert;
        this.attachedAt = attachedAt;
    }

    public void moveTo(CaseEntity target) {
        this.caseEntity = target;
    }

    public Long getId() { return id; }
    public CaseEntity getCaseEntity() { return caseEntity; }
    public AlertEntity getAlert() { return alert; }
    public Instant getAttachedAt() { return attachedAt; }
}
