package io.github.eunini.mrd.cases.repository;

import io.github.eunini.mrd.cases.domain.ReportEntity;
import io.github.eunini.mrd.cases.domain.ReportType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface ReportRepository extends Repository<ReportEntity, Long> {

    ReportEntity save(ReportEntity report);

    List<ReportEntity> findByCaseIdOrderByIdAsc(Long caseId);

    Optional<ReportEntity> findFirstByCaseIdAndReportTypeOrderByIdDesc(Long caseId, ReportType reportType);
}
