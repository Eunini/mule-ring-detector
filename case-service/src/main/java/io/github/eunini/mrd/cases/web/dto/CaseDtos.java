package io.github.eunini.mrd.cases.web.dto;

import io.github.eunini.mrd.cases.domain.AuditAction;
import io.github.eunini.mrd.cases.domain.CaseStatus;
import io.github.eunini.mrd.cases.domain.Disposition;
import io.github.eunini.mrd.cases.domain.FilingStatus;
import io.github.eunini.mrd.cases.domain.Priority;
import io.github.eunini.mrd.cases.domain.ReportType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Response and request bodies for the case API. */
public final class CaseDtos {

    private CaseDtos() {
    }

    public record CaseListItem(Long id, String reference, CaseStatus status, Priority priority, double maxScore,
                               int alertCount, int accountCount, BigDecimal totalAmountUsd, String assignee,
                               Instant createdAt, Instant updatedAt) {
    }

    public record CaseDetail(Long id, String reference, CaseStatus status, Disposition disposition,
                             Priority priority, double maxScore, int alertCount, BigDecimal totalAmountUsd,
                             String assignee, Long mergedIntoId, Instant createdAt, Instant updatedAt,
                             Instant closedAt, List<String> accounts, List<String> ringIds,
                             List<AlertSummary> alerts, Set<CaseStatus> allowedTransitions,
                             FilingRequestView filingRequest, List<ReportView> reports) {
    }

    public record AlertSummary(String alertId, Long txId, Instant timestamp, String fromAccount, String toAccount,
                               BigDecimal amountUsd, String currency, String paymentFormat, double score,
                               Double threshold, String modelVersion, String ringId, List<String> detectors) {
    }

    public record FilingRequestView(Long id, FilingStatus status, String requestedBy, Instant requestedAt,
                                    String requestComment, String decidedBy, Instant decidedAt,
                                    String decisionComment, Long reportId) {
    }

    public record ReportView(Long id, ReportType type, String contentType, String sha256, long sizeBytes,
                             String generatedBy, Instant generatedAt) {
    }

    public record AuditEventView(Long id, String actor, AuditAction action, CaseStatus fromStatus,
                                 CaseStatus toStatus, String details, Instant timestamp) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}
