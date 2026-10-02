package io.github.eunini.mrd.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eunini.mrd.cases.domain.AuditEvent;
import io.github.eunini.mrd.cases.repository.AuditEventRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AuditAppendOnlyIT extends IntegrationTestBase {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void auditRepositoryExposesNoUpdateOrDelete() {
        assertThat(Arrays.stream(AuditEventRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update") || name.startsWith("saveAll"));
    }

    @Test
    void auditRowsCannotBeRemovedThroughJpa() throws Exception {
        long caseId = caseIdOf(ingest(alert("au-1", "001:A", "002:B", null)), 0);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            AuditEvent event = entityManager.createQuery(
                    "select e from AuditEvent e where e.caseId = :id order by e.id", AuditEvent.class)
                    .setParameter("id", caseId).setMaxResults(1).getSingleResult();
            entityManager.remove(event);
            entityManager.flush();
        })).isInstanceOf(IllegalStateException.class).hasMessage("audit events are append-only");
        assertThat(auditActions(caseId)).containsExactly("CASE_CREATED", "ALERT_ATTACHED");
    }
}
