package io.github.eunini.mrd.cases.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** An alert as received from the detection engine. Alerts are immutable once stored. */
@Entity
@Table(name = "alert")
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false, updatable = false, unique = true)
    private String alertId;

    @Column(name = "tx_id", nullable = false, updatable = false)
    private Long txId;

    @Column(name = "event_ts", nullable = false, updatable = false)
    private Instant eventTs;

    @Column(name = "from_account", nullable = false, updatable = false)
    private String fromAccount;

    @Column(name = "to_account", nullable = false, updatable = false)
    private String toAccount;

    @Column(name = "amount_usd", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amountUsd;

    @Column(name = "currency", updatable = false)
    private String currency;

    @Column(name = "payment_format", updatable = false)
    private String paymentFormat;

    @Column(name = "score", nullable = false, updatable = false)
    private double score;

    @Column(name = "threshold", updatable = false)
    private Double threshold;

    @Column(name = "model_version", updatable = false)
    private String modelVersion;

    @Column(name = "ring_id", updatable = false)
    private String ringId;

    @Column(name = "detector_names", updatable = false)
    private String detectorNames;

    /** Full alert payload including detector evidence, stored as JSON text. */
    @Column(name = "evidence_json", nullable = false, updatable = false, columnDefinition = "text")
    private String evidenceJson;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "alert_account", joinColumns = @JoinColumn(name = "alert_pk"))
    private Set<AlertAccount> accounts = new LinkedHashSet<>();

    protected AlertEntity() {
    }

    public AlertEntity(String alertId, Long txId, Instant eventTs, String fromAccount, String toAccount,
                       BigDecimal amountUsd, String currency, String paymentFormat, double score,
                       Double threshold, String modelVersion, String ringId, String detectorNames,
                       String evidenceJson, Instant receivedAt) {
        this.alertId = alertId;
        this.txId = txId;
        this.eventTs = eventTs;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amountUsd = amountUsd;
        this.currency = currency;
        this.paymentFormat = paymentFormat;
        this.score = score;
        this.threshold = threshold;
        this.modelVersion = modelVersion;
        this.ringId = ringId;
        this.detectorNames = detectorNames;
        this.evidenceJson = evidenceJson;
        this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public String getAlertId() { return alertId; }
    public Long getTxId() { return txId; }
    public Instant getEventTs() { return eventTs; }
    public String getFromAccount() { return fromAccount; }
    public String getToAccount() { return toAccount; }
    public BigDecimal getAmountUsd() { return amountUsd; }
    public String getCurrency() { return currency; }
    public String getPaymentFormat() { return paymentFormat; }
    public double getScore() { return score; }
    public Double getThreshold() { return threshold; }
    public String getModelVersion() { return modelVersion; }
    public String getRingId() { return ringId; }
    public String getDetectorNames() { return detectorNames; }
    public String getEvidenceJson() { return evidenceJson; }
    public Instant getReceivedAt() { return receivedAt; }
    public Set<AlertAccount> getAccounts() { return accounts; }
}
