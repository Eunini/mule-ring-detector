package io.github.eunini.mrd.cases.repository;

import io.github.eunini.mrd.cases.domain.FilingRequest;
import io.github.eunini.mrd.cases.domain.FilingStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilingRequestRepository extends JpaRepository<FilingRequest, Long> {

    Optional<FilingRequest> findFirstByCaseIdAndStatus(Long caseId, FilingStatus status);

    Optional<FilingRequest> findFirstByCaseIdOrderByIdDesc(Long caseId);

    List<FilingRequest> findByCaseIdOrderByIdAsc(Long caseId);
}
