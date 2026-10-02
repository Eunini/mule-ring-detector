package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.domain.AuditAction;
import io.github.eunini.mrd.cases.domain.CaseAlert;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.domain.CaseStatus;
import io.github.eunini.mrd.cases.domain.FilingStatus;
import io.github.eunini.mrd.cases.repository.CaseAlertRepository;
import io.github.eunini.mrd.cases.repository.CaseRepository;
import io.github.eunini.mrd.cases.repository.FilingRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaches a freshly stored alert to a case.
 * <ul>
 *   <li>Candidates are active cases (OPEN, INVESTIGATING, ESCALATED) sharing any account
 *       of the alert (from, to or ring member) or the same ring id.</li>
 *   <li>No candidate: a new case is opened.</li>
 *   <li>Several candidates: all are merged into the oldest one, which keeps its status.
 *       Absorbed cases become MERGED and any pending filing request on them is cancelled.</li>
 * </ul>
 */
@Service
public class CaseGroupingService {

    private final CaseRepository caseRepository;
    private final CaseAlertRepository caseAlertRepository;
    private final FilingRequestRepository filingRequestRepository;
    private final AuditService auditService;
    private final Clock clock;

    public CaseGroupingService(CaseRepository caseRepository, CaseAlertRepository caseAlertRepository,
                               FilingRequestRepository filingRequestRepository, AuditService auditService,
                               Clock clock) {
        this.caseRepository = caseRepository;
        this.caseAlertRepository = caseAlertRepository;
        this.filingRequestRepository = filingRequestRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CaseEntity attach(AlertEntity alert, Set<String> accounts, String actor) {
        Instant now = clock.instant();
        List<CaseEntity> candidates = candidates(accounts, alert.getRingId());

        CaseEntity target;
        if (candidates.isEmpty()) {
            target = caseRepository.save(new CaseEntity(now));
            target.assignReference();
            auditService.record(target, actor, AuditAction.CASE_CREATED, null, CaseStatus.OPEN,
                    "Case opened for alert " + alert.getAlertId());
        } else {
            target = candidates.get(0);
            for (CaseEntity other : candidates.subList(1, candidates.size())) {
                merge(other, target, actor, now);
            }
        }

        caseAlertRepository.save(new CaseAlert(target, alert, now));
        target.getAccounts().addAll(accounts);
        target.recordAlert(alert.getScore(), alert.getAmountUsd(), now);
        auditService.record(target, actor, AuditAction.ALERT_ATTACHED, target.getStatus(), target.getStatus(),
                describe(alert));
        return target;
    }

    private List<CaseEntity> candidates(Set<String> accounts, String ringId) {
        Map<Long, CaseEntity> byId = new LinkedHashMap<>();
        if (!accounts.isEmpty()) {
            caseRepository.findByStatusInAndAnyAccount(CaseStatus.ACTIVE, accounts)
                    .forEach(c -> byId.putIfAbsent(c.getId(), c));
        }
        if (ringId != null && !ringId.isBlank()) {
            caseRepository.findByStatusInAndRingId(CaseStatus.ACTIVE, ringId)
                    .forEach(c -> byId.putIfAbsent(c.getId(), c));
        }
        return byId.values().stream()
                .sorted(Comparator.comparing(CaseEntity::getCreatedAt).thenComparing(CaseEntity::getId))
                .toList();
    }

    private void merge(CaseEntity absorbed, CaseEntity survivor, String actor, Instant now) {
        List<CaseAlert> links = caseAlertRepository.findByCaseIdOrderByEventTs(absorbed.getId());
        links.forEach(link -> link.moveTo(survivor));
        CaseStatus absorbedStatus = absorbed.getStatus();
        survivor.absorb(absorbed, now);
        absorbed.markMergedInto(survivor, now);

        filingRequestRepository.findFirstByCaseIdAndStatus(absorbed.getId(), FilingStatus.PENDING)
                .ifPresent(request -> request.decide(FilingStatus.CANCELLED, actor, now,
                        "Case merged into " + survivor.getReference()));

        auditService.record(survivor, actor, AuditAction.MERGED, survivor.getStatus(), survivor.getStatus(),
                "Absorbed " + absorbed.getReference() + " (" + links.size() + " alerts, status "
                        + absorbedStatus + ") after overlapping alert");
        auditService.record(absorbed, actor, AuditAction.MERGED, absorbedStatus, CaseStatus.MERGED,
                "Merged into " + survivor.getReference());
    }

    private static String describe(AlertEntity alert) {
        StringBuilder sb = new StringBuilder()
                .append("Alert ").append(alert.getAlertId())
                .append(" tx ").append(alert.getTxId())
                .append(" score ").append(String.format(java.util.Locale.ROOT, "%.3f", alert.getScore()))
                .append(" amount USD ").append(alert.getAmountUsd().toPlainString());
        if (alert.getRingId() != null) {
            sb.append(" ring ").append(alert.getRingId());
        }
        if (alert.getDetectorNames() != null && !alert.getDetectorNames().isBlank()) {
            sb.append(" detectors ").append(alert.getDetectorNames());
        }
        return sb.toString();
    }

    static Set<String> accountsOf(String from, String to, List<String> ringAccounts) {
        Set<String> accounts = new LinkedHashSet<>();
        accounts.add(from);
        accounts.add(to);
        accounts.addAll(ringAccounts);
        return accounts;
    }
}
