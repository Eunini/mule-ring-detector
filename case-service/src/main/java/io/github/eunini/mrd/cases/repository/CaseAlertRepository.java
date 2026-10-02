package io.github.eunini.mrd.cases.repository;

import io.github.eunini.mrd.cases.domain.CaseAlert;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseAlertRepository extends JpaRepository<CaseAlert, Long> {

    @Query("""
            select ca from CaseAlert ca join fetch ca.alert a
            where ca.caseEntity.id = :caseId
            order by a.eventTs asc, a.id asc
            """)
    List<CaseAlert> findByCaseIdOrderByEventTs(@Param("caseId") Long caseId);

    @Query("select ca from CaseAlert ca join fetch ca.caseEntity where ca.alert.alertId = :alertId")
    Optional<CaseAlert> findByAlertId(@Param("alertId") String alertId);
}
