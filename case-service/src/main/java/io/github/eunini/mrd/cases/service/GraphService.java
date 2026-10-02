package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.web.dto.AlertPayload;
import io.github.eunini.mrd.cases.web.dto.GraphView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Builds the evidence graph of a case: every alerted transaction plus every edge cited by a
 * detector, de-duplicated by transaction (the detector field lists all citing detectors).
 */
@Service
public class GraphService {

    private final EvidenceReader evidence;

    public GraphService(EvidenceReader evidence) {
        this.evidence = evidence;
    }

    public GraphView build(List<AlertEntity> alerts) {
        Map<String, EdgeAcc> edges = new LinkedHashMap<>();
        Set<String> ringMembers = new LinkedHashSet<>();
        Set<String> alertedAccounts = new LinkedHashSet<>();

        for (AlertEntity alert : alerts) {
            AlertPayload payload = evidence.read(alert);
            ringMembers.addAll(payload.ringAccountsOrEmpty());
            alertedAccounts.add(alert.getFromAccount());
            alertedAccounts.add(alert.getToAccount());
            EdgeAcc own = edges.computeIfAbsent(key(alert.getTxId(), alert.getFromAccount(), alert.getToAccount(),
                            alert.getEventTs()),
                    k -> new EdgeAcc(alert.getFromAccount(), alert.getToAccount(), alert.getAmountUsd(),
                            alert.getTxId(), alert.getEventTs()));
            own.alerted = true;
            own.detectors.add("alert");
            for (AlertPayload.Detector detector : payload.detectorsOrEmpty()) {
                if (detector.edges() == null) {
                    continue;
                }
                for (AlertPayload.Edge e : detector.edges()) {
                    EdgeAcc acc = edges.computeIfAbsent(key(e.txId(), e.from(), e.to(), e.timestamp()),
                            k -> new EdgeAcc(e.from(), e.to(), e.amountUsd(), e.txId(), e.timestamp()));
                    acc.detectors.add(detector.name());
                    if (e.laundering() != null) {
                        acc.laundering = Boolean.TRUE.equals(acc.laundering) || e.laundering();
                    }
                }
            }
        }

        Map<String, int[]> degree = new HashMap<>();
        for (EdgeAcc e : edges.values()) {
            degree.computeIfAbsent(e.source, k -> new int[2])[0]++;
            degree.computeIfAbsent(e.target, k -> new int[2])[1]++;
        }

        // Order nodes by first appearance along the time-ordered money flow, so that cycles and
        // chains read naturally in a circular layout; isolated ring members go last.
        Set<String> nodeIds = new LinkedHashSet<>();
        edges.values().stream()
                .sorted(Comparator.comparing((EdgeAcc e) -> e.timestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(e -> e.txId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(e -> {
                    nodeIds.add(e.source);
                    nodeIds.add(e.target);
                });
        nodeIds.addAll(new TreeSet<>(ringMembers));
        List<GraphView.Node> nodes = new ArrayList<>(nodeIds.size());
        for (String id : nodeIds) {
            int[] d = degree.getOrDefault(id, new int[2]);
            String role = d[0] > 0 && d[1] > 0 ? "intermediary" : d[0] > 0 ? "source" : d[1] > 0 ? "sink" : "member";
            AccountRef ref = AccountRef.parse(id);
            nodes.add(new GraphView.Node(id, ref.label(), role, ref.bankId(), ringMembers.contains(id),
                    alertedAccounts.contains(id)));
        }

        List<GraphView.Edge> edgeViews = edges.values().stream()
                .map(e -> new GraphView.Edge(e.source, e.target, e.amount, e.txId, e.timestamp,
                        String.join(",", e.detectors), e.laundering, e.alerted))
                .toList();
        return new GraphView(nodes, edgeViews);
    }

    private static String key(Long txId, String from, String to, Instant ts) {
        return txId != null ? "tx:" + txId : from + "|" + to + "|" + ts;
    }

    private static final class EdgeAcc {
        final String source;
        final String target;
        final BigDecimal amount;
        final Long txId;
        final Instant timestamp;
        final Set<String> detectors = new LinkedHashSet<>();
        Boolean laundering;
        boolean alerted;

        EdgeAcc(String source, String target, BigDecimal amount, Long txId, Instant timestamp) {
            this.source = source;
            this.target = target;
            this.amount = amount;
            this.txId = txId;
            this.timestamp = timestamp;
        }
    }
}
