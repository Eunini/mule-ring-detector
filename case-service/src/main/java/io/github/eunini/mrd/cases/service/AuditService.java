package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.domain.AuditAction;
import io.github.eunini.mrd.cases.domain.AuditEvent;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.domain.CaseStatus;
import io.github.eunini.mrd.cases.repository.AuditEventRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes append-only audit rows. Must be called inside the transaction of the audited change. */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent record(CaseEntity caseEntity, String actor, AuditAction action,
                             CaseStatus from, CaseStatus to, String details) {
        return repository.save(new AuditEvent(caseEntity.getId(), actor, action, from, to, details, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> trail(Long caseId) {
        return repository.findByCaseIdOrderByIdAsc(caseId);
    }
}
