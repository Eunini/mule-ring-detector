package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.domain.AuditEvent;
import io.github.eunini.mrd.cases.domain.CaseAlert;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.domain.CaseStateMachine;
import io.github.eunini.mrd.cases.domain.CaseStatus;
import io.github.eunini.mrd.cases.domain.FilingRequest;
import io.github.eunini.mrd.cases.domain.ReportEntity;
import io.github.eunini.mrd.cases.repository.CaseAlertRepository;
import io.github.eunini.mrd.cases.repository.CaseRepository;
import io.github.eunini.mrd.cases.repository.FilingRequestRepository;
import io.github.eunini.mrd.cases.repository.ReportRepository;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.AlertSummary;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.AuditEventView;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.CaseDetail;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.CaseListItem;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.FilingRequestView;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.PageResponse;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.ReportView;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CaseQueryService {

    private final CaseRepository caseRepository;
    private final CaseAlertRepository caseAlertRepository;
    private final FilingRequestRepository filingRequestRepository;
    private final ReportRepository reportRepository;
    private final AuditService auditService;

    public CaseQueryService(CaseRepository caseRepository, CaseAlertRepository caseAlertRepository,
                            FilingRequestRepository filingRequestRepository, ReportRepository reportRepository,
                            AuditService auditService) {
        this.caseRepository = caseRepository;
        this.caseAlertRepository = caseAlertRepository;
        this.filingRequestRepository = filingRequestRepository;
        this.reportRepository = reportRepository;
        this.auditService = auditService;
    }

    public PageResponse<CaseListItem> list(Collection<CaseStatus> statuses, Pageable pageable) {
        Collection<CaseStatus> filter = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(CaseStatus.class) : statuses;
        Page<CaseEntity> page = caseRepository.findByStatusIn(filter, pageable);
        List<CaseListItem> items = page.getContent().stream().map(CaseQueryService::toListItem).toList();
        return new PageResponse<>(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public CaseEntity require(Long id) {
        return caseRepository.findById(id).orElseThrow(() -> ApiException.notFound("case " + id + " not found"));
    }

    public List<AlertEntity> alerts(Long caseId) {
        return caseAlertRepository.findByCaseIdOrderByEventTs(caseId).stream().map(CaseAlert::getAlert).toList();
    }

    public CaseDetail detail(Long id) {
        CaseEntity c = require(id);
        List<AlertEntity> alerts = alerts(id);
        List<String> ringIds = alerts.stream().map(AlertEntity::getRingId).filter(Objects::nonNull)
                .distinct().sorted().toList();
        FilingRequestView filing = filingRequestRepository.findFirstByCaseIdOrderByIdDesc(id)
                .map(CaseQueryService::toView).orElse(null);
        List<ReportView> reports = reportRepository.findByCaseIdOrderByIdAsc(id).stream()
                .map(CaseQueryService::toView).toList();
        return new CaseDetail(c.getId(), c.getReference(), c.getStatus(), c.getDisposition(), c.getPriority(),
                c.getMaxScore(), c.getAlertCount(), c.getTotalAmountUsd(), c.getAssignee(), c.getMergedIntoId(),
                c.getCreatedAt(), c.getUpdatedAt(), c.getClosedAt(), c.getAccounts().stream().sorted().toList(),
                ringIds, alerts.stream().map(CaseQueryService::toSummary).toList(),
                CaseStateMachine.allowedFrom(c.getStatus()), filing, reports);
    }

    public List<AuditEventView> audit(Long id) {
        require(id);
        return auditService.trail(id).stream().map(CaseQueryService::toView).toList();
    }

    static CaseListItem toListItem(CaseEntity c) {
        return new CaseListItem(c.getId(), c.getReference(), c.getStatus(), c.getPriority(), c.getMaxScore(),
                c.getAlertCount(), c.getAccounts().size(), c.getTotalAmountUsd(), c.getAssignee(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    static AlertSummary toSummary(AlertEntity a) {
        List<String> detectors = a.getDetectorNames() == null || a.getDetectorNames().isBlank()
                ? List.of() : Arrays.asList(a.getDetectorNames().split(","));
        return new AlertSummary(a.getAlertId(), a.getTxId(), a.getEventTs(), a.getFromAccount(), a.getToAccount(),
                a.getAmountUsd(), a.getCurrency(), a.getPaymentFormat(), a.getScore(), a.getThreshold(),
                a.getModelVersion(), a.getRingId(), detectors);
    }

    static FilingRequestView toView(FilingRequest f) {
        return new FilingRequestView(f.getId(), f.getStatus(), f.getRequestedBy(), f.getRequestedAt(),
                f.getRequestComment(), f.getDecidedBy(), f.getDecidedAt(), f.getDecisionComment(), f.getReportId());
    }

    static ReportView toView(ReportEntity r) {
        return new ReportView(r.getId(), r.getReportType(), r.getContentType(), r.getSha256(), r.getSizeBytes(),
                r.getGeneratedBy(), r.getGeneratedAt());
    }

    static AuditEventView toView(AuditEvent e) {
        return new AuditEventView(e.getId(), e.getActor(), e.getAction(), e.getFromStatus(), e.getToStatus(),
                e.getDetails(), e.getCreatedAt());
    }
}
