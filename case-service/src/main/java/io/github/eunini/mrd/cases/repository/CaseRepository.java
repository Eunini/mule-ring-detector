package io.github.eunini.mrd.cases.repository;

import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.domain.CaseStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseRepository extends JpaRepository<CaseEntity, Long> {

    Page<CaseEntity> findByStatusIn(Collection<CaseStatus> statuses, Pageable pageable);

    @Query("""
            select distinct c from CaseEntity c join c.accounts acc
            where c.status in :statuses and acc in :accounts
            """)
    List<CaseEntity> findByStatusInAndAnyAccount(@Param("statuses") Collection<CaseStatus> statuses,
                                                 @Param("accounts") Collection<String> accounts);

    @Query("""
            select distinct ca.caseEntity from CaseAlert ca
            where ca.caseEntity.status in :statuses and ca.alert.ringId = :ringId
            """)
    List<CaseEntity> findByStatusInAndRingId(@Param("statuses") Collection<CaseStatus> statuses,
                                             @Param("ringId") String ringId);
}
