package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.config.UserDirectory;
import io.github.eunini.mrd.cases.domain.AuditAction;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.domain.CaseStateMachine;
import io.github.eunini.mrd.cases.domain.CaseStatus;
import io.github.eunini.mrd.cases.domain.Disposition;
import io.github.eunini.mrd.cases.domain.FilingRequest;
import io.github.eunini.mrd.cases.domain.FilingStatus;
import io.github.eunini.mrd.cases.domain.ReportEntity;
import io.github.eunini.mrd.cases.domain.ReportType;
import io.github.eunini.mrd.cases.repository.FilingRequestRepository;
import io.github.eunini.mrd.cases.repository.ReportRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analyst workflow and four-eyes STR filing. Every state change writes an audit row in
 * the same transaction. Role checks (who may call what) live in the security config;
 * this service enforces the rules that depend on case state and on who did what.
 */
@Service
@Transactional
public class CaseWorkflowService {

    private final CaseQueryService queries;
    private final FilingRequestRepository filingRequests;
    private final ReportRepository reports;
    private final AuditService audit;
    private final UserDirectory users;
    private final Clock clock;

    public CaseWorkflowService(CaseQueryService queries, FilingRequestRepository filingRequests,
                               ReportRepository reports, AuditService audit, UserDirectory users, Clock clock) {
        this.queries = queries;
        this.filingRequests = filingRequests;
        this.reports = reports;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
    }

    public CaseEntity assign(Long caseId, String assignee, String actor) {
        CaseEntity c = queries.require(caseId);
        requireActive(c);
        UserDirectory.UserProfile profile = users.find(assignee)
                .filter(UserDirectory.UserProfile::canWorkCases)
                .orElseThrow(() -> ApiException.badRequest("unknown analyst: " + assignee));
        String previous = c.getAssignee();
        c.assignTo(profile.username(), clock.instant());
        audit.record(c, actor, AuditAction.ASSIGNED, c.getStatus(), c.getStatus(),
                "Assigned to " + profile.username() + (previous == null ? "" : " (was " + previous + ")"));
        return c;
    }

    public CaseEntity transition(Long caseId, CaseStatus to, Disposition disposition, String comment, String actor) {
        CaseEntity c = queries.require(caseId);
        CaseStatus from = c.getStatus();
        if (!CaseStateMachine.canTransition(from, to)) {
            throw ApiException.conflict("illegal transition " + from + " -> " + to);
        }
        if (to == CaseStatus.CLOSED) {
            if (disposition == null) {
                throw ApiException.badRequest("closing a case requires a disposition");
            }
            if (disposition == Disposition.STR_FILED) {
                throw ApiException.conflict(
                        "STR_FILED requires a filing request approved by a different supervisor");
            }
        } else if (disposition != null) {
            throw ApiException.badRequest("disposition is only allowed when closing");
        }
        if (filingRequests.findFirstByCaseIdAndStatus(caseId, FilingStatus.PENDING).isPresent()) {
            throw ApiException.conflict("a filing request is pending; it must be approved or rejected first");
        }
        c.changeStatus(to, disposition, clock.instant());
        audit.record(c, actor, AuditAction.TRANSITION, from, to, transitionDetails(disposition, comment));
        return c;
    }

    public CaseEntity comment(Long caseId, String text, String actor) {
        CaseEntity c = queries.require(caseId);
        c.touch(clock.instant());
        audit.record(c, actor, AuditAction.COMMENT, c.getStatus(), c.getStatus(), text);
        return c;
    }

    public FilingRequest requestFiling(Long caseId, String comment, String actor) {
        CaseEntity c = queries.require(caseId);
        if (c.getStatus() != CaseStatus.ESCALATED) {
            throw ApiException.conflict("filing can only be requested for ESCALATED cases (case is " + c.getStatus() + ")");
        }
        if (filingRequests.findFirstByCaseIdAndStatus(caseId, FilingStatus.PENDING).isPresent()) {
            throw ApiException.conflict("a filing request is already pending");
        }
        ReportEntity str = reports.findFirstByCaseIdAndReportTypeOrderByIdDesc(caseId, ReportType.STR_XML)
                .orElseThrow(() -> ApiException.conflict("generate the STR report before requesting filing"));
        Instant now = clock.instant();
        FilingRequest request = filingRequests.save(new FilingRequest(caseId, str.getId(), actor, now, comment));
        c.touch(now);
        audit.record(c, actor, AuditAction.FILING_REQUESTED, c.getStatus(), c.getStatus(),
                "Filing requested for STR report #" + str.getId() + " (sha256 " + str.getSha256() + ")"
                        + (comment == null || comment.isBlank() ? "" : ": " + comment));
        return request;
    }

    public FilingRequest approveFiling(Long caseId, String comment, String actor) {
        CaseEntity c = queries.require(caseId);
        FilingRequest request = pending(caseId);
        requireSecondPerson(request, actor);
        if (c.getStatus() != CaseStatus.ESCALATED) {
            throw ApiException.conflict("case is no longer ESCALATED");
        }
        Instant now = clock.instant();
        request.decide(FilingStatus.APPROVED, actor, now, comment);
        CaseStatus from = c.getStatus();
        c.changeStatus(CaseStatus.CLOSED, Disposition.STR_FILED, now);
        audit.record(c, actor, AuditAction.FILING_APPROVED, from, CaseStatus.CLOSED,
                "STR filing approved (requested by " + request.getRequestedBy() + ", report #"
                        + request.getReportId() + "); disposition STR_FILED"
                        + (comment == null || comment.isBlank() ? "" : ": " + comment));
        return request;
    }

    public FilingRequest rejectFiling(Long caseId, String reason, String actor) {
        CaseEntity c = queries.require(caseId);
        FilingRequest request = pending(caseId);
        requireSecondPerson(request, actor);
        Instant now = clock.instant();
        request.decide(FilingStatus.REJECTED, actor, now, reason);
        CaseStatus from = c.getStatus();
        CaseStatus to = from == CaseStatus.ESCALATED ? CaseStatus.INVESTIGATING : from;
        c.changeStatus(to, null, now);
        audit.record(c, actor, AuditAction.FILING_REJECTED, from, to,
                "STR filing rejected (requested by " + request.getRequestedBy() + "); sent back: " + reason);
        return request;
    }

    private FilingRequest pending(Long caseId) {
        return filingRequests.findFirstByCaseIdAndStatus(caseId, FilingStatus.PENDING)
                .orElseThrow(() -> ApiException.conflict("no pending filing request"));
    }

    private static void requireSecondPerson(FilingRequest request, String actor) {
        if (request.getRequestedBy().equals(actor)) {
            throw ApiException.forbidden("four-eyes rule: the requester cannot decide their own filing request");
        }
    }

    private static void requireActive(CaseEntity c) {
        if (!c.getStatus().isActive()) {
            throw ApiException.conflict("case is " + c.getStatus());
        }
    }

    private static String transitionDetails(Disposition disposition, String comment) {
        StringBuilder sb = new StringBuilder();
        if (disposition != null) {
            sb.append("Disposition ").append(disposition);
        }
        if (comment != null && !comment.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(": ");
            }
            sb.append(comment);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
