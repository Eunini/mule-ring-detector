package io.github.eunini.mrd.cases.repository;

import io.github.eunini.mrd.cases.domain.AuditEvent;
import java.util.List;
import org.springframework.data.repository.Repository;

/** Insert and read only: no update or delete operations are exposed for audit rows. */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    List<AuditEvent> findByCaseIdOrderByIdAsc(Long caseId);

    long count();
}
