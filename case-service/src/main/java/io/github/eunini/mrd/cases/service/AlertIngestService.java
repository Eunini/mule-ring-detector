package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.domain.AccountRole;
import io.github.eunini.mrd.cases.domain.AlertAccount;
import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.domain.CaseAlert;
import io.github.eunini.mrd.cases.domain.CaseEntity;
import io.github.eunini.mrd.cases.repository.AlertRepository;
import io.github.eunini.mrd.cases.repository.CaseAlertRepository;
import io.github.eunini.mrd.cases.web.dto.AlertPayload;
import io.github.eunini.mrd.cases.web.dto.IngestResponse;
import io.github.eunini.mrd.cases.web.dto.IngestResponse.Outcome;
import io.github.eunini.mrd.cases.web.dto.IngestResponse.Result;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent alert ingestion. An alertId that was already stored is reported as a duplicate
 * and left untouched. Grouping is serialised within this instance so concurrent batches
 * cannot open two cases for the same ring.
 */
@Service
public class AlertIngestService {

    private final AlertRepository alertRepository;
    private final CaseAlertRepository caseAlertRepository;
    private final CaseGroupingService groupingService;
    private final EvidenceReader evidence;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ReentrantLock groupingLock = new ReentrantLock();

    public AlertIngestService(AlertRepository alertRepository, CaseAlertRepository caseAlertRepository,
                              CaseGroupingService groupingService, EvidenceReader evidence,
                              TransactionTemplate transactionTemplate, Clock clock) {
        this.alertRepository = alertRepository;
        this.caseAlertRepository = caseAlertRepository;
        this.groupingService = groupingService;
        this.evidence = evidence;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public IngestResponse ingest(List<AlertPayload> alerts, String actor) {
        groupingLock.lock();
        try {
            return transactionTemplate.execute(status -> doIngest(alerts, actor));
        } finally {
            groupingLock.unlock();
        }
    }

    private IngestResponse doIngest(List<AlertPayload> alerts, String actor) {
        List<Result> results = new ArrayList<>(alerts.size());
        Set<String> seenInBatch = new HashSet<>();
        int accepted = 0;
        int duplicates = 0;
        for (AlertPayload payload : alerts) {
            boolean duplicate = !seenInBatch.add(payload.alertId()) || alertRepository.existsByAlertId(payload.alertId());
            if (duplicate) {
                duplicates++;
                CaseEntity owner = caseAlertRepository.findByAlertId(payload.alertId())
                        .map(CaseAlert::getCaseEntity).orElse(null);
                results.add(new Result(payload.alertId(), Outcome.DUPLICATE,
                        owner == null ? null : owner.getId(), owner == null ? null : owner.getReference()));
                continue;
            }
            AlertEntity alert = alertRepository.save(toEntity(payload));
            Set<String> accounts = CaseGroupingService.accountsOf(
                    payload.fromAccount(), payload.toAccount(), payload.ringAccountsOrEmpty());
            CaseEntity owner = groupingService.attach(alert, accounts, actor);
            accepted++;
            results.add(new Result(payload.alertId(), Outcome.ACCEPTED, owner.getId(), owner.getReference()));
        }
        return new IngestResponse(alerts.size(), accepted, duplicates, results);
    }

    private AlertEntity toEntity(AlertPayload p) {
        String detectorNames = p.detectorsOrEmpty().stream()
                .map(AlertPayload.Detector::name).distinct().collect(Collectors.joining(","));
        AlertEntity alert = new AlertEntity(
                p.alertId(), p.txId(), p.timestamp(), p.fromAccount(), p.toAccount(),
                p.amountUsd().setScale(2, RoundingMode.HALF_UP), p.currency(), p.paymentFormat(),
                p.score(), p.threshold(), p.modelVersion(), p.ringId(),
                detectorNames.length() > 512 ? detectorNames.substring(0, 512) : detectorNames,
                evidence.write(p), clock.instant());
        alert.getAccounts().add(new AlertAccount(p.fromAccount(), AccountRole.FROM));
        alert.getAccounts().add(new AlertAccount(p.toAccount(), AccountRole.TO));
        p.ringAccountsOrEmpty().forEach(a -> alert.getAccounts().add(new AlertAccount(a, AccountRole.RING)));
        return alert;
    }
}
