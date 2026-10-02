package io.github.eunini.mrd.cases.repository;

import io.github.eunini.mrd.cases.domain.AlertEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<AlertEntity, Long> {

    boolean existsByAlertId(String alertId);

    Optional<AlertEntity> findByAlertId(String alertId);
}
